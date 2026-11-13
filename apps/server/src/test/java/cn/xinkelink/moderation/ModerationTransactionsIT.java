package cn.xinkelink.moderation;

import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db","auth"})
class ModerationTransactionsIT {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    long reporter,author,adminId,postId;
    String token,adminToken,authorToken;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @BeforeEach void fixture() {
        var user=auth.loginUser(new WechatIdentity("moderation-transactions-it",UUID.randomUUID().toString()));
        reporter=Long.parseLong(user.me().id());token=user.accessToken();
        user=auth.loginUser(new WechatIdentity("moderation-transactions-it",UUID.randomUUID().toString()));
        author=Long.parseLong(user.me().id());authorToken=user.accessToken();
        jdbc.update("UPDATE xl_user SET version=0 WHERE id=?",author);
        String account="mod-tx-"+UUID.randomUUID();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,?,'事务审核员',UTC_TIMESTAMP(3))",account,passwords.encode("test-pass"));
        var admin=auth.loginAdmin(account,"test-pass");adminToken=admin.tokens().accessToken();adminId=Long.parseLong(admin.tokens().admin().id());
        String request=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_post(author_id,category,content,client_request_id,request_hash) VALUES (?,'campus','并发举报目标',?,UNHEX(SHA2('fixture',256)))",author,request);
        postId=jdbc.queryForObject("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?",Long.class,author,request);
    }
    @AfterEach void cleanup() {
        if(reporter==0)return;
        for(long id:jdbc.queryForList("SELECT id FROM xl_report WHERE reporter_id IN (?,?)",Long.class,reporter,author)) {
            jdbc.update("DELETE FROM xl_media_ref WHERE owner_type IN ('report_evidence','report_snapshot') AND owner_id=?",id);
            jdbc.update("DELETE FROM xl_moderation_action WHERE report_id=?",id);
        }
        jdbc.update("DELETE FROM xl_moderation_action WHERE target_user_id IN (?,?)",reporter,author);
        jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id IN (?,?)",reporter,author);
        jdbc.update("DELETE FROM xl_report WHERE reporter_id IN (?,?)",reporter,author);
        jdbc.update("DELETE FROM xl_post WHERE id=?",postId);
        jdbc.update("DELETE FROM xl_image_asset WHERE owner_user_id IN (?,?)",reporter,author);
        jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id IN (?,?)",reporter,author);
        jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='admin' AND subject_id=?",adminId);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE admin_id=?",Integer.class,adminId)==0)
            jdbc.update("DELETE FROM xl_admin WHERE id=?",adminId);
        else jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?",adminId);
        jdbc.update("DELETE FROM xl_user WHERE id IN (?,?)",reporter,author);
    }
    @Test void concurrentRetriesCommitOneCaseAndOneEvidenceReference() throws Exception {
        String key="moderation-transactions-it/"+UUID.randomUUID();
        jdbc.update("INSERT INTO xl_image_asset(owner_user_id,purpose,storage_key,media_type,byte_size,width,height,sha256,expires_at) VALUES (?,'report',?,'image/png',10,10,10,UNHEX(SHA2('fixture',256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",reporter,key);
        String image=Long.toString(jdbc.queryForObject("SELECT id FROM xl_image_asset WHERE storage_key=?",Long.class,key));
        String body=json.writeValueAsString(Map.of("targetType","post","targetId",Long.toString(postId),"reasonCode","other","description","并发重试","evidenceImageIds",List.of(image),"clientRequestId",UUID.randomUUID().toString()));
        List<String> ids=concurrently(()->{
            var response=mvc.perform(post("/api/v1/reports").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
            return json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asString();
        });
        assertEquals(1,ids.stream().distinct().count());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_report WHERE reporter_id=?",Integer.class,reporter));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_media_ref WHERE owner_type='report_evidence' AND owner_id=?",Integer.class,ids.getFirst()));
    }
    @Test void concurrentResolutionsCommitOnceAndRejectSecondCommand() throws Exception {
        String id=report();
        String body=json.writeValueAsString(Map.of("action","hide_post","reason","审核确认","expectedVersion",0,"confirmed",true));
        List<Integer> results=concurrently(()->mvc.perform(post("/api/v1/admin/reports/"+id+"/resolution").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        assertEquals(List.of(200,409),results.stream().sorted().toList());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE report_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT version FROM xl_post WHERE id=?",Integer.class,postId));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id IN (?,?) AND event_kind='moderation_warning'",Integer.class,reporter,author));
    }
    @Test void outerTransactionFailureRollsBackTargetCaseHistoryAndNotices() throws Exception {
        String id=report();
        String body=json.writeValueAsString(Map.of("action","hide_post","reason","审核确认","expectedVersion",0,"confirmed",true));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            try {
                mvc.perform(post("/api/v1/admin/reports/"+id+"/resolution").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            } catch(Exception failure) {throw new RuntimeException(failure);}
            throw new IllegalStateException("验证外层事务失败");
        }));
        assertEquals("visible",jdbc.queryForObject("SELECT status FROM xl_post WHERE id=?",String.class,postId));
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM xl_report WHERE id=?",String.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE report_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id IN (?,?)",Integer.class,reporter,author));
    }
    @Test void concurrentUserCommandsWithSameVersionCommitOneHistory() throws Exception {
        String body=json.writeValueAsString(Map.of("channel","comment","enabled",true,"reason","限制评论","expectedVersion",0,"confirmed",true));
        List<Integer> statuses=concurrently(()->mvc.perform(post("/api/v1/admin/users/"+author+"/restrictions").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        assertEquals(List.of(200,409),statuses.stream().sorted().toList());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE target_user_id=?",Integer.class,author));
    }
    @Test void mutualUserReportsTakeUserLocksInStableOrder() throws Exception {
        java.util.concurrent.atomic.AtomicInteger participant=new java.util.concurrent.atomic.AtomicInteger();
        List<Integer> statuses=concurrently(()->{
            boolean first=participant.getAndIncrement()==0;
            String body=json.writeValueAsString(Map.of("targetType","user","targetId",Long.toString(first?author:reporter),"reasonCode","harassment","description","并发用户举报","evidenceImageIds",List.of(),"clientRequestId",UUID.randomUUID().toString()));
            return mvc.perform(post("/api/v1/reports").header("Authorization","Bearer "+(first?token:authorToken)).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
        });
        assertEquals(List.of(201,201),statuses);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_report WHERE reporter_id IN (?,?)",Integer.class,reporter,author));
    }
    @Test void resolvingOppositeUserCasesCommitsBothNotificationsWithoutDeadlock() throws Exception {
        List<String> cases=new ArrayList<>();
        for(boolean first:List.of(true,false)) {
            String body=json.writeValueAsString(Map.of("targetType","user","targetId",Long.toString(first?author:reporter),"reasonCode","other","description","相互举报案件","evidenceImageIds",List.of(),"clientRequestId",UUID.randomUUID().toString()));
            var result=mvc.perform(post("/api/v1/reports").header("Authorization","Bearer "+(first?token:authorToken)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
            cases.add(json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asString());
        }
        java.util.concurrent.atomic.AtomicInteger participant=new java.util.concurrent.atomic.AtomicInteger();
        String command=json.writeValueAsString(Map.of("action","restrict_user","reason","审核确认","expectedVersion",0,"confirmed",true));
        List<Integer> statuses=concurrently(()->mvc.perform(post("/api/v1/admin/reports/"+cases.get(participant.getAndIncrement())+"/resolution").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(command)).andReturn().getResponse().getStatus());
        assertEquals(List.of(200,200),statuses);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE target_user_id IN (?,?) AND source='report'",Integer.class,reporter,author));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id IN (?,?)",Integer.class,reporter,author));
    }
    @Test void expiredTemporaryBanNormalizesOnceAndPreservesManualRestriction() throws Exception {
        jdbc.update("UPDATE xl_user SET ban_state='temporary',ban_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND),restrict_publish=1 WHERE id=?",author);
        concurrently(()->{
            mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+authorToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("restricted"));
            return true;
        });
        assertEquals("none",jdbc.queryForObject("SELECT ban_state FROM xl_user WHERE id=?",String.class,author));
        assertTrue(jdbc.queryForObject("SELECT restrict_publish FROM xl_user WHERE id=?",Boolean.class,author));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE target_user_id=? AND action='temporary_ban_expired' AND source='system'",Integer.class,author));
    }
    private String report() throws Exception {
        String body=json.writeValueAsString(Map.of("targetType","post","targetId",Long.toString(postId),"reasonCode","other","description","待审核","evidenceImageIds",List.of(),"clientRequestId",UUID.randomUUID().toString()));
        var result=mvc.perform(post("/api/v1/reports").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asString();
    }
    private static <T> List<T> concurrently(Callable<T> operation) throws Exception {
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new IllegalStateException("并发启动超时");return operation.call();}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            List<T> results=new ArrayList<>();for(Future<T> future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;
        }
    }
}
