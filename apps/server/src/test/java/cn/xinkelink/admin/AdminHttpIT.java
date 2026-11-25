package cn.xinkelink.admin;

import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db","auth"})
@Transactional
class AdminHttpIT {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;
    String adminToken, userToken, marker;
    long owner, postId, rootId, replyId;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @BeforeEach void fixture() {
        marker="admin-it-"+UUID.randomUUID();
        var login=auth.loginUser(new WechatIdentity("admin-http-it",marker));
        owner=Long.parseLong(login.me().id());userToken=login.accessToken();
        jdbc.update("UPDATE xl_user SET nickname='管理员测试作者',major='软件工程',version=0 WHERE id=?",owner);
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,?, '管理员测试员',UTC_TIMESTAMP(3))",marker,passwords.encode("test-pass"));
        adminToken=auth.loginAdmin(marker,"test-pass").tokens().accessToken();
        postId=insert("xl_post","author_id,category,content,is_anonymous,anonymous_label",List.of(owner,"campus",marker+"匿名内容",true,"匿名同学"));
        rootId=insert("xl_comment","target_type,target_id,author_id,content",List.of("post",postId,owner,marker+"根评论"));
        replyId=insert("xl_comment","target_type,target_id,author_id,content,root_id,reply_to_id",List.of("post",postId,owner,marker+"回复",rootId,rootId));
    }

    @Test void filtersAllForumCategoriesAndRejectsSortNamesAsCategories() throws Exception {
        for(String category:List.of("campus","lost_found","study","help","secondhand","complain","buddy")) {
            jdbc.update("UPDATE xl_post SET category=? WHERE id=?",category,postId);
            mvc.perform(get("/api/v1/admin/posts").param("keyword",marker).param("category",category).header("Authorization","Bearer "+adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].category").value(category));
        }
        for(String category:List.of("recommended","latest","product"))
            mvc.perform(get("/api/v1/admin/posts").param("category",category).header("Authorization","Bearer "+adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test void everyReadEndpointRequiresAdminAndReturnsItsContract() throws Exception {
        List<String> paths=List.of("/dashboard?range=7d","/posts?keyword="+marker,"/posts/"+postId,"/posts/"+postId+"/comments",
                "/comments?keyword="+marker,"/comments/"+replyId+"/context",
                "/users?keyword="+owner,"/users/"+owner,"/users/"+owner+"/content?type=post","/users/"+owner+"/reports","/users/"+owner+"/moderation-history");
        for(String path:paths) {
            mvc.perform(get("/api/v1/admin"+path).header("Authorization","Bearer "+userToken)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin"+path).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
        }
    }
    @Test void anonymousOwnershipAndUncollectedMetricsAreAdminOnly() throws Exception {
        var result=read("/posts/"+postId);
        assertEquals(Long.toString(owner),result.path("post").path("author").path("id").asString());
        assertTrue(result.path("post").path("viewCount").isNull());
        assertEquals(2,result.path("commentSummary").path("total").asInt());
        var dashboard=read("/dashboard?range=7d");
        assertEquals(7,dashboard.path("trends").size());
        assertTrue(dashboard.path("trends").get(0).path("dau").isNull());
        assertTrue(dashboard.has("rangeStart"));
        assertFalse(result.toString().contains("wechat"));
    }
    @Test void listFiltersEscapeWildcardsKeepGlobalStatsAndRejectInvalidPagination() throws Exception {
        var posts=read("/posts?keyword="+marker+"&page=1&pageSize=20");
        assertEquals(1,posts.path("total").asInt());assertEquals(20,posts.path("pageSize").asInt());
        assertTrue(posts.path("stats").path("total").asLong()>=1);
        mvc.perform(get("/api/v1/admin/posts").param("keyword","%").header("Authorization","Bearer "+adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        assertEquals(0,read("/posts?keyword="+marker+"&page=999").path("items").size());
        for(String path:List.of("/posts?pageSize=11","/comments?level=third","/users?restriction=other","/posts?from=2026-10-25T00:00:00Z&to=2026-10-24T00:00:00Z","/dashboard?range=year"))
            mvc.perform(get("/api/v1/admin"+path).header("Authorization","Bearer "+adminToken)).andExpect(status().isBadRequest());
    }
    @Test void statusBatchRejectsStaleItemBeforeAnyWritesAndReportsActualChangedCount() throws Exception {
        command("/posts/status",Map.of("items",List.of(item(postId,0),item(999999999,0)),"targetStatus","hidden"),404);
        assertEquals("visible",jdbc.queryForObject("SELECT status FROM xl_post WHERE id=?",String.class,postId));
        command("/posts/status",Map.of("items",List.of(item(postId,1)),"targetStatus","hidden"),409);
        var result=command("/posts/status",Map.of("items",List.of(item(postId,0)),"targetStatus","hidden"),200);
        assertEquals(1,result.path("changedCount").asInt());assertEquals(1,result.path("items").get(0).path("newVersion").asInt());
        command("/posts/status",Map.of("items",List.of(item(postId,1)),"targetStatus","hidden"),409);
        command("/comments/status",Map.of("items",List.of(item(rootId,0)),"targetStatus","hidden"),200);
        assertEquals("hidden",jdbc.queryForObject("SELECT status FROM xl_comment WHERE id=?",String.class,rootId));
        assertEquals(0,read("/posts/"+postId).path("post").path("visibleCommentCount").asInt());
    }
    @Test void deletingRootDeduplicatesSelectedReplyAndPreservesDeletedContext() throws Exception {
        var result=command("/comments/delete",Map.of("items",List.of(item(rootId,0),item(replyId,0)),"confirmed",true),200);
        assertEquals(2,result.path("changedCount").asInt());assertEquals(1,result.path("removedReplyCount").asInt());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_comment WHERE id IN (?,?) AND deleted_at IS NOT NULL",Integer.class,rootId,replyId));
        var context=read("/comments/"+replyId+"/context");
        assertTrue(context.path("selected").path("content").isNull());
        assertEquals("deleted",context.path("visibility").path("selected").asString());
    }
    @Test void postDeletionRequiresVersionAndRemovesBothCommentLevels() throws Exception {
        mvc.perform(delete("/api/v1/admin/posts/"+postId).header("Authorization","Bearer "+adminToken)).andExpect(status().isPreconditionRequired());
        mvc.perform(delete("/api/v1/admin/posts/"+postId).header("Authorization","Bearer "+adminToken).header("If-Match","\"0\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.removedCommentCount").value(2));
        assertEquals("deleted",read("/posts/"+postId).path("post").path("availability").asString());
        assertTrue(read("/posts/"+postId).path("post").path("content").isNull());
    }
    @Test void commentContextPaginatesRepliesAndBindsCursorToSelectedRoot() throws Exception {
        insert("xl_comment","target_type,target_id,author_id,content,root_id,reply_to_id",List.of("post",postId,owner,"第二条回复",rootId,replyId));
        var first=read("/comments/"+replyId+"/context?size=1");
        assertEquals(Long.toString(rootId),first.path("root").path("id").asString());
        assertEquals(1,first.path("replies").path("items").size());assertTrue(first.path("replies").path("hasMore").asBoolean());
        String cursor=first.path("replies").path("nextCursor").asString();
        var second=read("/comments/"+replyId+"/context?size=1&replyCursor="+cursor);
        assertEquals(1,second.path("replies").path("items").size());assertFalse(second.path("replies").path("hasMore").asBoolean());
    }
    @Test void expiredBansProjectManualRestrictionsAndUserReportsDeduplicateRoles() throws Exception {
        jdbc.update("UPDATE xl_user SET ban_state='temporary',ban_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY),restrict_comment=1 WHERE id=?",owner);
        var user=read("/users/"+owner);
        assertEquals("restricted",user.path("status").asString());assertFalse(user.path("restrictions").path("publish").asBoolean());
        jdbc.update("INSERT INTO xl_report(reporter_id,target_type,target_id,target_user_id,reason_code,description,target_snapshot,client_request_id,request_hash) VALUES (?,'user',?,?,'other','说明',JSON_OBJECT('id',?),?,UNHEX(SHA2('fixture',256)))",owner,owner,owner,Long.toString(owner),marker);
        var reports=read("/users/"+owner+"/reports");
        assertEquals(1,reports.path("total").asInt());assertEquals(2,reports.path("items").get(0).path("roles").size());
        assertFalse(reports.toString().contains("targetSnapshot"));
    }
    private tools.jackson.databind.JsonNode read(String path)throws Exception {
        var response=mvc.perform(get("/api/v1/admin"+path).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }
    private tools.jackson.databind.JsonNode command(String path,Object body,int code)throws Exception {
        MockHttpServletRequestBuilder request=post("/api/v1/admin"+path).header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var response=mvc.perform(request).andExpect(status().is(code)).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }
    private Map<String,Object> item(long id,long version){return Map.of("id",Long.toString(id),"expectedVersion",version);}
    private long insert(String table,String columns,List<Object> values) {
        String request=UUID.randomUUID().toString();List<Object> args=new ArrayList<>(values);args.add(request);
        jdbc.update("INSERT INTO "+table+"("+columns+",client_request_id,request_hash) VALUES ("+String.join(",",Collections.nCopies(args.size(),"?"))+",UNHEX(SHA2('fixture',256)))",args.toArray());
        return jdbc.queryForObject("SELECT id FROM "+table+" WHERE client_request_id=?",Long.class,request);
    }
}
