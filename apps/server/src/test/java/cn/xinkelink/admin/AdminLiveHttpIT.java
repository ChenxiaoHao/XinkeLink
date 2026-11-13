package cn.xinkelink.admin;

import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"db","auth"})
class AdminLiveHttpIT {
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;
    @MockitoSpyBean AdminData data;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    long owner,adminId,postId,otherPost,rootId,replyId;
    String token,userToken,marker;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(new byte[32]));}
    @BeforeEach void fixtures(){
        marker="admin-live-it-"+UUID.randomUUID();var user=auth.loginUser(new WechatIdentity("admin-live-it",marker));owner=Long.parseLong(user.me().id());userToken=user.accessToken();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,?, '真实HTTP测试员',UTC_TIMESTAMP(3))",marker,passwords.encode("test-pass"));
        var admin=auth.loginAdmin(marker,"test-pass");adminId=Long.parseLong(admin.tokens().admin().id());token=admin.tokens().accessToken();
        postId=insert("xl_post","author_id,category,content",List.of(owner,"campus",marker+"保密正文"));
        otherPost=insert("xl_post","author_id,category,content",List.of(owner,"campus",marker+"另一条正文"));
        rootId=insert("xl_comment","target_type,target_id,author_id,content",List.of("post",postId,owner,"一级评论"));
        replyId=insert("xl_comment","target_type,target_id,author_id,content,root_id,reply_to_id",List.of("post",postId,owner,"二级回复",rootId,rootId));
    }
    @AfterEach void retainImmutableEvidenceAndDeactivateOnlyThisFixture(){
        if(owner==0)return;
        // 已提交的审计和处置历史保持只追加，精确停用本测试的随机主体及内容。
        jdbc.update("UPDATE xl_comment SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE author_id=?",owner);
        jdbc.update("UPDATE xl_post SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE author_id=?",owner);
        jdbc.update("UPDATE xl_auth_session SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)) WHERE (subject_type='user' AND subject_id=?) OR (subject_type='admin' AND subject_id=?)",owner,adminId);
        jdbc.update("UPDATE xl_user SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",owner);
        jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?",adminId);
    }
    @Test void realHttpReadsAllThirteenRoutesAndRejectsUserCredentials()throws Exception {
        for(String path:List.of("/dashboard?range=7d","/posts?keyword="+marker,"/posts/"+postId,"/posts/"+postId+"/comments","/comments?keyword=评论","/comments/"+replyId+"/context","/users","/users/"+owner,"/users/"+owner+"/content?type=post","/users/"+owner+"/reports","/users/"+owner+"/moderation-history")){
            assertEquals(200,request("GET",path,null,token).statusCode(),path);
            assertEquals(403,request("GET",path,null,userToken).statusCode(),path);
        }
    }
    @Test void concurrentHttpCommandsCommitOneVersionAndAuditBothOutcomes()throws Exception {
        var body=Map.of("items",List.of(item(postId,0)),"targetStatus","hidden","reason",marker+"保密理由");
        try(var executor=Executors.newFixedThreadPool(2)){
            CountDownLatch start=new CountDownLatch(1);Callable<Integer> action=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return request("POST","/posts/status",body,token).statusCode();};
            var first=executor.submit(action);var second=executor.submit(action);start.countDown();var statuses=new ArrayList<>(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
        }
        assertEquals(1,jdbc.queryForObject("SELECT version FROM xl_post WHERE id=?",Integer.class,postId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE admin_id=?",Integer.class,adminId));
        var logs=jdbc.queryForList("SELECT outcome,result_code,target_count,changed_fields FROM xl_audit_log WHERE admin_id=? ORDER BY id",adminId);
        assertEquals(2,logs.size());assertEquals(Set.of("success","business_rejected"),new HashSet<>(logs.stream().map(row->row.get("outcome").toString()).toList()));
        assertEquals(1,logs.stream().mapToInt(row->((Number)row.get("target_count")).intValue()).sum());
        assertFalse(logs.toString().contains(marker));
    }
    @Test void executionFailureAfterHistoryInsertRollsBackEveryRowAndAuditsError()throws Exception {
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("controlled admin history failure");}).when(data).history(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString(),anyMap(),anyMap());
        var response=request("POST","/posts/status",Map.of("items",List.of(item(postId,0),item(otherPost,0)),"targetStatus","hidden"),token);
        assertEquals(500,response.statusCode());assertFalse(response.body().contains("controlled"));
        assertEquals(0,jdbc.queryForObject("SELECT SUM(version) FROM xl_post WHERE id IN (?,?)",Integer.class,postId,otherPost));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE admin_id=?",Integer.class,adminId));
        assertEquals("execution_error",jdbc.queryForObject("SELECT outcome FROM xl_audit_log WHERE admin_id=?",String.class,adminId));
    }
    @Test void mixedBatchAuditCountsOnlyChangedSelectionAndAllUnchangedIsRejected()throws Exception {
        jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?",otherPost);
        var response=request("POST","/posts/status",Map.of("items",List.of(item(postId,0),item(otherPost,0)),"targetStatus","hidden"),token);
        assertEquals(200,response.statusCode());JsonNode result=json.readTree(response.body()).path("data");assertEquals(1,result.path("changedCount").asInt());assertEquals(1,result.path("skippedCount").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT target_count FROM xl_audit_log WHERE admin_id=? AND outcome='success'",Integer.class,adminId));
        assertEquals(409,request("POST","/posts/status",Map.of("items",List.of(item(postId,1),item(otherPost,0)),"targetStatus","hidden"),token).statusCode());
        assertEquals("STATE_UNCHANGED",jdbc.queryForObject("SELECT result_code FROM xl_audit_log WHERE admin_id=? AND outcome='business_rejected'",String.class,adminId));
    }
    @Test void realHttpDeleteRoutesPreserveRelationshipsAndRecordTwoOperations()throws Exception {
        assertEquals(200,request("POST","/comments/delete",Map.of("items",List.of(item(rootId,0),item(replyId,0)),"confirmed",true),token).statusCode());
        var context=request("GET","/comments/"+replyId+"/context",null,token);assertEquals(200,context.statusCode());assertTrue(json.readTree(context.body()).path("data").path("selected").path("content").isNull());
        var delete=HttpRequest.newBuilder(uri("/posts/"+postId)).header("Authorization","Bearer "+token).header("If-Match","\"0\"").DELETE().build();
        assertEquals(200,client.send(delete,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE admin_id=? AND outcome='success'",Integer.class,adminId));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_comment WHERE id IN (?,?)",Integer.class,rootId,replyId));
    }
    @Test void realHttpCommentVisibilityKeepsReplyState()throws Exception {
        assertEquals(200,request("POST","/comments/status",Map.of("items",List.of(item(rootId,0)),"targetStatus","hidden"),token).statusCode());
        var context=json.readTree(request("GET","/comments/"+replyId+"/context",null,token).body()).path("data");
        assertFalse(context.path("visibility").path("effectiveVisible").asBoolean());
        assertEquals("visible",context.path("selected").path("status").asString());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE admin_id=? AND outcome='success'",Integer.class,adminId));
    }
    private URI uri(String path){return URI.create("http://127.0.0.1:"+port+"/api/v1/admin"+path);}
    private HttpResponse<String> request(String method,String path,Object body,String credential)throws Exception{
        var request=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+credential);
        if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private Map<String,Object> item(long id,long version){return Map.of("id",Long.toString(id),"expectedVersion",version);}
    private long insert(String table,String columns,List<Object> values){String request=UUID.randomUUID().toString();List<Object> args=new ArrayList<>(values);args.add(request);jdbc.update("INSERT INTO "+table+"("+columns+",client_request_id,request_hash) VALUES ("+String.join(",",Collections.nCopies(args.size(),"?"))+",UNHEX(SHA2('fixture',256)))",args.toArray());return jdbc.queryForObject("SELECT id FROM "+table+" WHERE client_request_id=?",Long.class,request);}
}
