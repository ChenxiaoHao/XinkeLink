package cn.xinkelink.moderation;

import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "auth"})
@Transactional
class ModerationHttpIT {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cn.xinkelink.beta.BetaService platformPhase;
    // Business regressions run in the public phase; beta access has its own integration suite.
    @org.junit.jupiter.api.BeforeEach void publicPlatformFixture(){org.mockito.Mockito.doReturn(false).when(platformPhase).isBeta();}

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    long reporter, author, adminId;
    String token, adminToken, csrf;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("xinkelink.auth.admin.allowed-origins", () -> "https://moderation.example");
    }

    @BeforeEach
    void fixture() {
        var login = auth.loginUser(new WechatIdentity("moderation-http-it", UUID.randomUUID().toString()));
        reporter = Long.parseLong(login.me().id()); token = login.accessToken();
        author = Long.parseLong(auth.loginUser(new WechatIdentity("moderation-http-it", UUID.randomUUID().toString())).me().id());
        // 将治理夹具版本固定为0，不把登录触活导致的版本变更写进测试预期。
        jdbc.update("UPDATE xl_user SET version=0 WHERE id=?",author);
        String account = "mod-" + UUID.randomUUID();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,?, '审核管理员',UTC_TIMESTAMP(3))", account, passwords.encode("test-pass"));
        var admin = auth.loginAdmin(account, "test-pass");
        adminToken = admin.tokens().accessToken(); csrf = admin.tokens().csrfToken();
        adminId = Long.parseLong(admin.tokens().admin().id());
    }

    @Test
    void createsCommentReportWithoutLeakingAnonymousPostAuthor() throws Exception {
        long post = postFixture();
        long comment = comment(post);
        var result = mvc.perform(post("/api/v1/reports").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload("comment", comment))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.targetUser").doesNotExist()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asString();
        assertEquals(author, jdbc.queryForObject("SELECT target_user_id FROM xl_report WHERE id=?", Long.class, id));
    }

    @Test
    void requestRetryUsesInitialPayloadAndPendingGuardPreventsDuplicateCases() throws Exception {
        var body = payload("post", postFixture());
        String id = submit(body);
        assertEquals(id, submit(body));
        body.put("description", "不同说明");
        report(body, 409, "DUPLICATE_REQUEST_CONFLICT");
        body.put("clientRequestId", UUID.randomUUID().toString());
        report(body, 409, "REPORT_PENDING");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM xl_report WHERE reporter_id=?", Integer.class, reporter));
    }

    @Test
    void evidenceIsOwnedReportPurposeTemporaryAndFailedBindingLeavesNoCase() throws Exception {
        long post = postFixture();
        for (String image : List.of(image(author,"report"), image(reporter,"post"))) {
            var body = payload("post", post); body.put("evidenceImageIds", List.of(image));
            report(body, 403, "IMAGE_FORBIDDEN");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM xl_report WHERE reporter_id=?", Integer.class, reporter));
    }

    @Test
    void preservesOriginalImageReferencesAndOnlyAdminCanReadCase() throws Exception {
        long post = postFixture();
        String original = image(author,"post");
        jdbc.update("UPDATE xl_image_asset SET state='ready',expires_at=NULL WHERE id=?", original);
        jdbc.update("INSERT INTO xl_media_ref(owner_type,owner_id,position,image_id) VALUES ('post',?,0,?)",post,original);
        String evidence = image(reporter,"report");
        var body = payload("post",post); body.put("evidenceImageIds",List.of(evidence));
        String id = submit(body);
        jdbc.update("DELETE FROM xl_media_ref WHERE owner_type='post' AND owner_id=?",post);
        jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",post);
        mvc.perform(get("/api/v1/admin/reports/"+id).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/reports/"+id).header("Authorization","Bearer "+adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetSnapshot.content").value("匿名原文"))
                .andExpect(jsonPath("$.data.evidence[0].imageId").value(evidence))
                .andExpect(jsonPath("$.data.targetSnapshot.images[0].imageId").value(original))
                .andExpect(jsonPath("$.data.target.availability").value("deleted"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_media_ref WHERE owner_type='report_snapshot' AND owner_id=? AND image_id=?",Integer.class,id,original));
    }

    @Test
    void missingConfirmationStaleVersionAndDeletedTargetDoNotResolve() throws Exception {
        long post = postFixture(); String id = submit(payload("post",post));
        resolve(id, Map.of("action","hide_post","reason","违规","expectedVersion",0),400,"VALIDATION_FAILED");
        resolve(id, Map.of("action","hide_post","reason","违规","expectedVersion",1,"confirmed",true),409,"VERSION_CONFLICT");
        jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",post);
        resolve(id, command("hide_post",0),409,"STATE_CONFLICT");
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM xl_report WHERE id=?",String.class,id));
        resolve(id,command("reject",0),200,"OK");
        resolve(id,command("reject",1),409,"ALREADY_PROCESSED");
    }

    @Test
    void resolvingCommentHidesItAndCreatesOneHistoryAndSafeReceipts() throws Exception {
        long comment = comment(postFixture()); String id = submit(payload("comment",comment));
        mvc.perform(post("/api/v1/admin/reports/"+id+"/resolution").header("Authorization","Bearer "+adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command("hide_comment",0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.report.status").value("processed"))
                .andExpect(jsonPath("$.data.effects.changedCount").value(1))
                .andExpect(jsonPath("$.data.report.history[0].action").value("hide_comment"));
        assertEquals("hidden",jdbc.queryForObject("SELECT status FROM xl_comment WHERE id=?",String.class,comment));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE report_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=? AND event_kind='moderation_warning'",Integer.class,reporter));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=? AND event_kind='moderation_warning'",Integer.class,author));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=? AND (summary LIKE '%匿名原文%' OR summary LIKE '%被举报评论%')",Integer.class,reporter));
    }

    @Test
    void restrictionsAreIndependentAndReleaseClearsTemporaryLayerAndManualFlags() throws Exception {
        adminCommand("restrictions",Map.of("channel","publish","enabled",true,"reason","限制发布","expectedVersion",0,"confirmed",true),200,"OK");
        adminCommand("restrictions",Map.of("channel","publish","enabled",true,"reason","重复","expectedVersion",1,"confirmed",true),409,"STATE_UNCHANGED");
        adminCommand("ban",Map.of("mode","temporary","durationDays",7,"reason","封禁","expectedVersion",1,"confirmed",true),200,"OK");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_auth_session WHERE subject_type='user' AND subject_id=? AND revoked_at IS NULL",Integer.class,author));
        adminCommand("restrictions",Map.of("channel","publish","enabled",false,"reason","解除","expectedVersion",2,"confirmed",true),409,"STATE_CONFLICT");
        adminCommand("release",Map.of("reason","提前解除","expectedVersion",2,"confirmed",true),200,"OK");
        assertEquals("none",jdbc.queryForObject("SELECT ban_state FROM xl_user WHERE id=?",String.class,author));
        assertFalse(jdbc.queryForObject("SELECT restrict_publish FROM xl_user WHERE id=?",Boolean.class,author));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE source='user_management' AND target_user_id=?",Integer.class,author));
    }

    @Test
    void expiredTemporaryBanAcceptsCurrentAdminVersionAndPreservesManualFlags() throws Exception {
        jdbc.update("UPDATE xl_user SET ban_state='temporary',ban_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND),restrict_publish=1 WHERE id=?",author);
        adminCommand("restrictions",Map.of("channel","comment","enabled",true,"reason","限制评论","expectedVersion",0,"confirmed",true),200,"OK");
        assertEquals("none",jdbc.queryForObject("SELECT ban_state FROM xl_user WHERE id=?",String.class,author));
        assertTrue(jdbc.queryForObject("SELECT restrict_publish FROM xl_user WHERE id=?",Boolean.class,author));
        assertTrue(jdbc.queryForObject("SELECT restrict_comment FROM xl_user WHERE id=?",Boolean.class,author));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE target_user_id=?",Integer.class,author));
    }

    @Test
    void permanentBanCannotBeDowngradedOrReleasedByAnyModerationCommand() throws Exception {
        adminCommand("ban",Map.of("mode","permanent","reason","永久封禁","expectedVersion",0,"confirmed",true),200,"OK");
        adminCommand("ban",Map.of("mode","temporary","durationDays",7,"reason","降级","expectedVersion",1,"confirmed",true),409,"STATE_CONFLICT");
        adminCommand("release",Map.of("reason","解除","expectedVersion",1,"confirmed",true),409,"STATE_CONFLICT");
        String id=submit(payload("user",author));
        resolve(id,command("restrict_user",0),200,"OK");
        assertEquals("permanent",jdbc.queryForObject("SELECT ban_state FROM xl_user WHERE id=?",String.class,author));
        assertNull(jdbc.queryForObject("SELECT ban_until FROM xl_user WHERE id=?",Object.class,author));
    }

    @Test
    void adminQueueUsesGlobalStatsAndRestrictsEvidenceToDetail() throws Exception {
        String first=submit(payload("post",postFixture()));
        submit(payload("user",author));
        mvc.perform(get("/api/v1/admin/reports").param("keyword","请审核该内容").param("pageSize","10").header("Authorization","Bearer "+adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].evidence").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].targetSnapshot").doesNotExist())
                .andExpect(jsonPath("$.data.stats.pending").isNumber());
        mvc.perform(get("/api/v1/admin/reports").param("pageSize","11").header("Authorization","Bearer "+adminToken)).andExpect(status().isBadRequest());
        resolve(first,command("reject",0),200,"OK");
    }

    @Test
    void queueTimeRangeIncludesFromExcludesToAndRejectsEmptyRange() throws Exception {
        String keyword="范围-"+UUID.randomUUID();
        var firstBody=payload("post",postFixture());firstBody.put("description",keyword);
        var secondBody=payload("user",author);secondBody.put("description",keyword);
        String first=submit(firstBody),second=submit(secondBody);
        jdbc.update("UPDATE xl_report SET created_at='2026-10-25 00:00:00.000' WHERE id=?",first);
        jdbc.update("UPDATE xl_report SET created_at='2026-10-25 01:00:00.000' WHERE id=?",second);
        mvc.perform(get("/api/v1/admin/reports").param("keyword",keyword).param("from","2026-10-25T00:00:00Z").param("to","2026-10-25T01:00:00Z").header("Authorization","Bearer "+adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first));
        mvc.perform(get("/api/v1/admin/reports").param("from","2026-10-25T01:00:00Z").param("to","2026-10-25T01:00:00Z").header("Authorization","Bearer "+adminToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void submissionRejectsMessagesHiddenParentsAndForgedOwnership() throws Exception {
        report(payload("message",1),400,"UNSUPPORTED_TARGET");
        long post=postFixture(),comment=comment(post);
        jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?",post);
        report(payload("comment",comment),404,"TARGET_UNAVAILABLE");
        var body=payload("user",author);body.put("targetOwner",Long.toString(reporter));
        report(body,400,"VALIDATION_FAILED");
        mvc.perform(get("/api/v1/admin/reports").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }

    @Test
    void existingMessageCaseHidesOnlyTheReportedMessageAndPreservesItsSequence() throws Exception {
        long low=Math.min(reporter,author),high=Math.max(reporter,author);
        jdbc.update("INSERT INTO xl_conversation(low_user_id,high_user_id,next_sequence) VALUES(?,?,2)",low,high);
        long conversation=jdbc.queryForObject("SELECT id FROM xl_conversation WHERE low_user_id=? AND high_user_id=?",Long.class,low,high);
        jdbc.update("INSERT INTO xl_conversation_member(conversation_id,user_id) VALUES(?,?),(?,?)",conversation,reporter,conversation,author);
        String request=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_message(conversation_id,sender_id,sequence_no,client_message_id,request_hash,text) VALUES(?,?,1,?,UNHEX(SHA2(UUID(),256)),'仅案件授权的原始消息')",conversation,author,request);
        long message=jdbc.queryForObject("SELECT id FROM xl_message WHERE sender_id=? AND client_message_id=?",Long.class,author,request);
        jdbc.update("UPDATE xl_conversation SET last_message_id=?,last_message_at=UTC_TIMESTAMP(3) WHERE id=?",message,conversation);
        String reportRequest=UUID.randomUUID().toString();
        // 只准备既有案件夹具，不增加普通用户的私信举报入口。
        jdbc.update("INSERT INTO xl_report(reporter_id,target_type,target_id,target_user_id,reason_code,description,target_snapshot,client_request_id,request_hash) VALUES(?,'message',?,?,'harassment','指定消息案件',?, ?,UNHEX(SHA2(UUID(),256)))",reporter,message,author,json.writeValueAsString(Map.of("text","仅案件授权的原始消息")),reportRequest);
        String report=jdbc.queryForObject("SELECT id FROM xl_report WHERE reporter_id=? AND client_request_id=?",String.class,reporter,reportRequest);
        resolve(report,command("hide_message",0),200,"OK");
        mvc.perform(get("/api/v1/conversations/"+conversation+"/messages").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].sequence").value("1"))
                .andExpect(jsonPath("$.data.items[0].visibility").value("hidden"))
                .andExpect(jsonPath("$.data.items[0].text").isEmpty()).andExpect(jsonPath("$.data.items[0].senderId").isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message WHERE conversation_id=?",Integer.class,conversation));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE report_id=?",Integer.class,report));
    }

    private String submit(Map<String,Object> body) throws Exception {
        var result=mvc.perform(post("/api/v1/reports").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asString();
    }
    private void report(Map<String,Object> body,int status,String code) throws Exception {
        mvc.perform(post("/api/v1/reports").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
    private void resolve(String id,Map<String,Object> body,int status,String code) throws Exception {
        mvc.perform(post("/api/v1/admin/reports/"+id+"/resolution").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
    private void adminCommand(String route,Map<String,Object> body,int status,String code) throws Exception {
        mvc.perform(post("/api/v1/admin/users/"+author+"/"+route).header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
    private static Map<String,Object> command(String action,long version) {return Map.of("action",action,"reason","审核确认违规","expectedVersion",version,"confirmed",true);}
    private String image(long owner,String purpose) {
        String key="moderation-http-it/"+UUID.randomUUID();
        jdbc.update("INSERT INTO xl_image_asset(owner_user_id,purpose,storage_key,media_type,byte_size,width,height,sha256,expires_at) VALUES (?,?,?,'image/png',100,100,100,UNHEX(SHA2('fixture',256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",owner,purpose,key);
        return Long.toString(jdbc.queryForObject("SELECT id FROM xl_image_asset WHERE storage_key=?",Long.class,key));
    }
    private Map<String,Object> payload(String type, long id) {
        return new LinkedHashMap<>(Map.of("targetType", type, "targetId", Long.toString(id), "reasonCode", "harassment",
                "description", "请审核该内容", "evidenceImageIds", List.of(), "clientRequestId", UUID.randomUUID().toString()));
    }
    private long postFixture() {
        String request = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_post(author_id,category,content,is_anonymous,anonymous_label,client_request_id,request_hash) VALUES (?,'campus','匿名原文',1,'匿名同学',?,UNHEX(SHA2('fixture',256)))", author, request);
        return jdbc.queryForObject("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?", Long.class, author, request);
    }
    private long comment(long post) {
        String request = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_comment(target_type,target_id,author_id,content,client_request_id,request_hash) VALUES ('post',?,?,'被举报评论',?,UNHEX(SHA2('fixture',256)))", post, author, request);
        return jdbc.queryForObject("SELECT id FROM xl_comment WHERE author_id=? AND client_request_id=?", Long.class, author, request);
    }
}
