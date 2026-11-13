package cn.xinkelink.integration;

import cn.xinkelink.auth.*;
import cn.xinkelink.auth.wechat.WechatIdentity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 资料、设置和只读审计经过真实HTTP、事务及MySQL，身份准备不冒充真实微信。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"db","auth"})
class Day5SharedHttpIT {
    @Autowired AuthService auth;@Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder passwords;@Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    final HttpClient client=HttpClient.newHttpClient();long admin;String adminToken;AuthModels.UserTokens user,peer;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);registry.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(key));
    }
    @BeforeEach void fixture() {
        String account="day5-shared-"+UUID.randomUUID();jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,?,?,UTC_TIMESTAMP(3))",account,passwords.encode("test-only"),"联调测试员");
        admin=jdbc.queryForObject("SELECT id FROM xl_admin WHERE login_name=?",Long.class,account);adminToken=auth.loginAdmin(account,"test-only").tokens().accessToken();
        user=auth.loginUser(new WechatIdentity("day5-shared",UUID.randomUUID().toString()));peer=auth.loginUser(new WechatIdentity("day5-shared",UUID.randomUUID().toString()));
    }
    @AfterEach void cleanup() {
        for(var actor:List.of(user,peer)) {
            jdbc.update("DELETE FROM xl_user_block WHERE owner_id=? OR blocked_user_id=?",actor.me().id(),actor.me().id());
            jdbc.update("DELETE FROM xl_user_preference WHERE user_id=?",actor.me().id());
            jdbc.update("UPDATE xl_user SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",actor.me().id());
            jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",actor.me().id());
        }
        jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?",admin);
        jdbc.update("UPDATE xl_auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE subject_type='admin' AND subject_id=?",admin);
    }
    @Test void profilePatchesValidateFieldsAndVersionWithoutExposingIdentity() throws Exception {
        long original=user.me().version();
        JsonNode saved=call("PATCH","/me",Map.of("nickname","联调同学","major","软件工程","className","1班","enrollmentYear","2024","expectedVersion",original),user.accessToken(),200).get("data");
        assertEquals("联调同学",saved.get("nickname").stringValue());assertEquals(original+1,saved.get("version").longValue());assertTrue(saved.get("selfReported").booleanValue());
        call("PATCH","/me",Map.of("bio","过期编辑","expectedVersion",original),user.accessToken(),409);
        call("PATCH","/me",Map.of("studentId","123","expectedVersion",original+1),user.accessToken(),400);
        call("PATCH","/me",Map.of("nickname"," ","expectedVersion",original+1),user.accessToken(),400);
        call("PATCH","/me",Map.of("nickname","未登录","expectedVersion",1),null,401);
        call("PATCH","/me",Map.of("nickname","管理员不是学生","expectedVersion",1),adminToken,403);
    }
    @Test void preferencesAndIdempotentBlocksArePerUser() throws Exception {
        JsonNode defaults=call("GET","/me/preferences",null,user.accessToken(),200).get("data");assertTrue(defaults.get("messages").booleanValue());
        JsonNode changed=call("PATCH","/me/preferences",Map.of("messages",false,"sound",true,"expectedVersion",0),user.accessToken(),200).get("data");
        assertEquals(1,changed.get("version").intValue());assertFalse(changed.get("messages").booleanValue());
        call("PATCH","/me/preferences",Map.of("sound",false,"expectedVersion",0),user.accessToken(),409);
        assertTrue(call("GET","/me/preferences",null,peer.accessToken(),200).at("/data/messages").booleanValue());
        for(int i=0;i<2;i++)call("PUT","/me/blocks/"+peer.me().id(),null,user.accessToken(),200);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_block WHERE owner_id=?",Integer.class,user.me().id()));
        call("PUT","/me/blocks/"+user.me().id(),null,user.accessToken(),400);
        for(int i=0;i<2;i++)call("DELETE","/me/blocks/"+peer.me().id(),null,user.accessToken(),200);
    }
    @Test void settingsAreVersionedConfigurationOnlyAndAuditIsSafeReadOnly() throws Exception {
        call("GET","/admin/settings",null,user.accessToken(),403);call("GET","/admin/audit-events",null,user.accessToken(),403);
        JsonNode initial=call("GET","/admin/settings",null,adminToken,200).get("data");long version=initial.get("version").longValue();
        var original=json.convertValue(initial.get("contentReview"),Map.class);
        Map<String,Object> update=new LinkedHashMap<>(original);update.put("sensitiveWordIntercept",!initial.at("/contentReview/sensitiveWordIntercept").booleanValue());update.put("expectedVersion",version);
        JsonNode saved=call("PATCH","/admin/settings/content-review",update,adminToken,200).get("data");
        assertEquals("configuration_only",saved.get("effectMode").stringValue());assertFalse(saved.at("/system/automaticReview").booleanValue());
        call("PATCH","/admin/settings/content-review",update,adminToken,409);
        var restore=new LinkedHashMap<String,Object>(original);restore.put("expectedVersion",saved.get("version").longValue());call("PATCH","/admin/settings/content-review",restore,adminToken,200);
        String query="/admin/audit-events?adminId="+admin+"&module=settings";JsonNode list=call("GET",query,null,adminToken,200).get("data");
        assertEquals(3,list.get("total").intValue());
        String event=list.get("items").get(0).get("id").stringValue();String body=call("GET","/admin/audit-events/"+event,null,adminToken,200).toString();
        for(String secret:List.of("password","target_internal_id","test-only","refreshToken","sensitiveWords\":[\""))assertFalse(body.contains(secret));
        call("DELETE","/admin/audit-events/"+event,null,adminToken,405);
        var csv=raw("GET",query.replace("audit-events?","audit-events/export?"),null,adminToken);
        assertEquals(200,csv.statusCode());assertTrue(csv.body().startsWith("\uFEFF"));assertTrue(csv.body().contains("business_rejected"));
        call("GET","/admin/audit-events?outcome=invalid",null,adminToken,400);
    }
    private JsonNode call(String method,String path,Object body,String token,int status) throws Exception {
        var response=raw(method,path,body,token);assertEquals(status,response.statusCode(),method+" "+path+" "+response.body());return json.readTree(response.body());
    }
    private HttpResponse<String> raw(String method,String path,Object body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
