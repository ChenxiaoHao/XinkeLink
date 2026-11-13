package cn.xinkelink.beta;

import cn.xinkelink.auth.AuthModels.UserTokens;
import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MySQL + Redis + HTTP/WS. Never targets an ordinary development database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"db","auth"})
class BetaAccessIT {
    @Value("${local.server.port}") int port;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final List<String> users=new ArrayList<>(), admins=new ArrayList<>();
    final List<WebSocket> sockets=new ArrayList<>();
    boolean isolatedDatabase;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        r.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(key));}

    @BeforeEach void isolate(){
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).endsWith("_test"),"beta tests require an isolated _test database");
        isolatedDatabase=true;
        if(tablesExist()){jdbc.update("DELETE FROM xl_beta_code");jdbc.update("DELETE FROM xl_beta_stage");}
        Set<String> keys=redis.keys("xinkelink:auth:rate:beta:*");if(!keys.isEmpty())redis.delete(keys);
    }
    @Test void anonymousCanDiscoverTheOpenPhaseWithoutReadingBusinessContent()throws Exception{
        JsonNode status=call("GET","/api/v1/beta/access",null,null,200);
        assertEquals("open",status.get("phase").stringValue());assertFalse(status.get("authenticated").booleanValue());assertFalse(status.get("activated").booleanValue());
        assertEquals(Set.of("phase","authenticated","activated"),status.propertyNames());
    }
    @Test void initializationIsAtomicAndIdempotentAndPhaseUsesOptimisticVersion()throws Exception{
        String admin=admin();UserTokens user=user();
        call("GET","/api/v1/admin/beta",null,user.accessToken(),403);
        JsonNode initial=call("GET","/api/v1/admin/beta",null,admin,200);
        assertEquals(0,initial.get("total").intValue());
        call("PATCH","/api/v1/admin/beta",Map.of("phase","beta","expectedVersion",initial.get("version").longValue()),admin,409);
        var race=race(()->call("POST","/api/v1/admin/beta/initialize",Map.of(),admin,200),()->call("POST","/api/v1/admin/beta/initialize",Map.of(),admin,200));
        JsonNode batch=race.getFirst();assertEquals(batch,race.getLast());
        assertEquals("beta",batch.get("phase").stringValue());assertEquals(30,batch.get("total").intValue());assertEquals(30,batch.get("remaining").intValue());
        Set<String> codes=new HashSet<>();for(JsonNode code:batch.get("codes")){assertTrue(code.get("code").stringValue().matches("XK-[A-Z2-9]{6}-[A-Z2-9]{6}"));codes.add(code.get("code").stringValue());assertTrue(code.get("id").isString());assertEquals("unused",code.get("status").stringValue());assertTrue(code.get("user").isNull());assertTrue(code.get("usedAt").isNull());assertTrue(code.get("createdAt").stringValue().endsWith("Z"));}assertEquals(30,codes.size());
        JsonNode open=call("PATCH","/api/v1/admin/beta",Map.of("phase","open","expectedVersion",batch.get("version").longValue()),admin,200);
        assertEquals("open",open.get("phase").stringValue());assertEquals(batch.get("version").longValue()+1,open.get("version").longValue());
        assertEquals("VERSION_CONFLICT",error("PATCH","/api/v1/admin/beta",Map.of("phase","beta","expectedVersion",batch.get("version").longValue()),admin,409));
        assertEquals(open,call("POST","/api/v1/admin/beta/initialize",Map.of(),admin,200));
    }
    @Test void normalizedCodesBindOnceAndNeverConsumeASecondCodeAcrossRelogin()throws Exception{
        String admin=admin();JsonNode batch=initialize(admin);UserTokens first=user(),second=user();
        String a=code(batch,0),b=code(batch,1);
        JsonNode activated=call("POST","/api/v1/beta/redeem",Map.of("code"," \n"+a.toLowerCase(Locale.ROOT).replace("-"," ")+"\t"),first.accessToken(),200);
        assertTrue(activated.get("activated").booleanValue());assertEquals(3,activated.size());
        call("POST","/api/v1/beta/redeem",Map.of("code",b),first.accessToken(),200);
        assertEquals("BETA_CODE_UNAVAILABLE",error("POST","/api/v1/beta/redeem",Map.of("code",a),second.accessToken(),400));
        assertEquals("BETA_CODE_UNAVAILABLE",error("POST","/api/v1/beta/redeem",Map.of("code","incorrect"),second.accessToken(),400));
        call("POST","/api/v1/beta/redeem",Map.of("code",b),second.accessToken(),200);
        JsonNode managed=call("GET","/api/v1/admin/beta",null,admin,200);
        assertEquals(30,managed.get("total").intValue());assertEquals(2,managed.get("used").intValue());assertEquals(28,managed.get("remaining").intValue());
        assertEquals(first.me().id(),managed.at("/codes/0/user/id").stringValue());assertNotNull(managed.at("/codes/0/user/nickname"));assertTrue(managed.at("/codes/0/usedAt").stringValue().endsWith("Z"));
        String openId=jdbc.queryForObject("SELECT wechat_open_id FROM xl_user WHERE id=?",String.class,first.me().id());
        var relogin=auth.loginUser(new WechatIdentity("it-beta-access",openId));
        assertTrue(call("GET","/api/v1/beta/access",null,relogin.accessToken(),200).get("activated").booleanValue());
        call("GET","/api/v1/posts",null,first.accessToken(),200);
    }
    @Test void sameCodeHasOneWinnerAndSameAccountCannotConsumeTwoCodes()throws Exception{
        String admin=admin();JsonNode batch=initialize(admin);var a=user();var b=user();
        var results=race(()->request("POST","/api/v1/beta/redeem",Map.of("code",code(batch,0)),a.accessToken()),()->request("POST","/api/v1/beta/redeem",Map.of("code",code(batch,0)),b.accessToken()));
        assertEquals(List.of(200,400),results.stream().map(HttpResponse::statusCode).sorted().toList());
        UserTokens unbound=results.getFirst().statusCode()==200?b:a;
        var same=race(()->request("POST","/api/v1/beta/redeem",Map.of("code",code(batch,1)),unbound.accessToken()),()->request("POST","/api/v1/beta/redeem",Map.of("code",code(batch,2)),unbound.accessToken()));
        assertTrue(same.stream().allMatch(r->r.statusCode()==200));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_beta_code WHERE user_id IS NOT NULL",Integer.class));
    }
    @Test void betaDeniesVisitorsAndUnactivatedAccountsWhileKeepingProfileAndAdminAvailable()throws Exception{
        String admin=admin();initialize(admin);UserTokens user=user();
        assertEquals("BETA_ACCESS_REQUIRED",error("GET","/api/v1/posts",null,null,403));
        for(String path:List.of("/api/v1/posts","/api/v1/search?q=校园","/api/v1/me/unread","/api/v1/users/"+user.me().id(),"/api/v1/images/1/access"))
            assertEquals("BETA_ACCESS_REQUIRED",error("GET",path,null,user.accessToken(),403));
        assertEquals("BETA_ACCESS_REQUIRED",error("POST","/api/v1/posts",Map.of(),user.accessToken(),403));
        call("GET","/api/v1/me",null,user.accessToken(),200);
        call("PATCH","/api/v1/me",Map.of("nickname","内测验证账号","expectedVersion",user.me().version()),user.accessToken(),200);
        call("GET","/api/v1/admin/beta",null,admin,200);
        assertFalse(call("GET","/api/v1/beta/access",null,user.accessToken(),200).get("activated").booleanValue());
        var failed=assertThrows(CompletionException.class,()->connect(user.accessToken(),new Listener()));
        assertEquals(403,((WebSocketHandshakeException)failed.getCause()).getResponse().statusCode());
    }
    @Test void reenabledBetaClosesAnExistingUnactivatedSocketAndActivatedUserCanReconnect()throws Exception{
        String admin=admin();JsonNode batch=initialize(admin);UserTokens user=user();
        JsonNode open=call("PATCH","/api/v1/admin/beta",Map.of("phase","open","expectedVersion",batch.get("version").longValue()),admin,200);
        call("GET","/api/v1/posts",null,null,200);
        Listener listener=new Listener();connect(user.accessToken(),listener);
        call("PATCH","/api/v1/admin/beta",Map.of("phase","beta","expectedVersion",open.get("version").longValue()),admin,200);
        assertTrue(listener.closed.await(3,TimeUnit.SECONDS));assertEquals(1008,listener.closeCode);
        call("POST","/api/v1/beta/redeem",Map.of("code",code(batch,0)),user.accessToken(),200);
        assertFalse(connect(user.accessToken(),new Listener()).isInputClosed());
    }
    @Test void redemptionUsesBothAccountAndTrustedIpRateLimits()throws Exception{
        String admin=admin();initialize(admin);UserTokens user=user();
        for(int i=0;i<5;i++)call("POST","/api/v1/beta/redeem",Map.of("code","wrong"),user.accessToken(),400);
        assertEquals("RATE_LIMITED",error("POST","/api/v1/beta/redeem",Map.of("code","wrong"),user.accessToken(),429));
        UserTokens another=user();assertEquals("RATE_LIMITED",error("POST","/api/v1/beta/redeem",Map.of("code","wrong"),another.accessToken(),429));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_beta_code WHERE user_id IS NOT NULL",Integer.class));
    }
    @Test void nativeImageGrantsRespectBetaAndOwnAvatarWorksBeforeActivation()throws Exception{
        String admin=admin();UserTokens owner=user(),outsider=user();
        String image=upload("post",owner.accessToken(),201).get("id").stringValue();
        String post=call("POST","/api/v1/posts",Map.of("content","内测图片验证","category","study","imageIds",List.of(image),"anonymous",false,"clientRequestId",UUID.randomUUID().toString()),owner.accessToken(),201).get("id").stringValue();
        String old=call("GET","/api/v1/images/"+image+"/access",null,null,200).get("url").stringValue();
        assertEquals(200,request("GET",old,null,null).statusCode());
        JsonNode batch=initialize(admin);
        assertEquals("BETA_ACCESS_REQUIRED",error("GET",old,null,null,403));
        upload("post",owner.accessToken(),403);
        String avatar=upload("avatar",owner.accessToken(),201).get("id").stringValue();
        JsonNode me=call("GET","/api/v1/me",null,owner.accessToken(),200);
        JsonNode profile=call("PATCH","/api/v1/me",Map.of("avatarImageId",avatar,"expectedVersion",me.get("version").longValue()),owner.accessToken(),200);
        String own=profile.at("/avatar/url").stringValue();assertEquals(200,request("GET",own,null,null).statusCode());
        assertNotNull(auth.loginUser(new WechatIdentity("it-beta-access",jdbc.queryForObject("SELECT wechat_open_id FROM xl_user WHERE id=?",String.class,owner.me().id()))).me().avatar());
        assertEquals("BETA_ACCESS_REQUIRED",error("GET","/api/v1/images/"+avatar+"/access",null,outsider.accessToken(),403));
        call("POST","/api/v1/beta/redeem",Map.of("code",code(batch,0)),owner.accessToken(),200);
        call("GET","/api/v1/posts/"+post,null,owner.accessToken(),200);
        String bound=call("GET","/api/v1/images/"+image+"/access",null,owner.accessToken(),200).get("url").stringValue();
        assertEquals(200,request("GET",bound,null,null).statusCode());
        assertEquals("BETA_ACCESS_REQUIRED",error("GET",old,null,null,403));
    }
    JsonNode upload(String purpose,String token,int expected)throws Exception{
        ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB),"png",png);
        String boundary="beta-"+UUID.randomUUID();ByteArrayOutputStream body=new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"+purpose+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"beta.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));body.write(png.toByteArray());body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/images")).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),"image upload purpose="+purpose);return json.readTree(response.body()).get("data");
    }
    JsonNode initialize(String admin)throws Exception{return call("POST","/api/v1/admin/beta/initialize",Map.of(),admin,200);}
    String code(JsonNode batch,int index){return batch.get("codes").get(index).get("code").stringValue();}
    UserTokens user(){var value=auth.loginUser(new WechatIdentity("it-beta-access",UUID.randomUUID().toString()));users.add(value.me().id());return value;}
    String admin(){String account="it-beta-"+UUID.randomUUID();jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES(?,?,'内测验证管理员',UTC_TIMESTAMP(3))",account,passwords.encode("it-beta-password"));admins.add(jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM xl_admin WHERE login_name=?",String.class,account));return auth.loginAdmin(account,"it-beta-password").tokens().accessToken();}
    HttpResponse<String> request(String method,String path,Object body,String token)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));if(token!=null)builder.header("Authorization","Bearer "+token);if(body!=null)builder.header("Content-Type","application/json");return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode call(String method,String path,Object body,String token,int expected)throws Exception{var response=request(method,path,body,token);assertEquals(expected,response.statusCode(),"Unexpected HTTP status for "+path);return json.readTree(response.body()).get("data");}
    String error(String method,String path,Object body,String token,int expected)throws Exception{var response=request(method,path,body,token);assertEquals(expected,response.statusCode(),path);return json.readTree(response.body()).get("code").stringValue();}
    <T> List<T> race(Callable<T> a,Callable<T> b)throws Exception{try(var executor=Executors.newFixedThreadPool(2)){var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var one=executor.submit(()->{ready.countDown();go.await();return a.call();});var two=executor.submit(()->{ready.countDown();go.await();return b.call();});assertTrue(ready.await(3,TimeUnit.SECONDS));go.countDown();return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));}}
    WebSocket connect(String token,Listener listener){var socket=client.newWebSocketBuilder().header("Authorization","Bearer "+token).connectTimeout(Duration.ofSeconds(3)).buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/v1/ws"),listener).join();sockets.add(socket);return socket;}
    static class Listener implements WebSocket.Listener{final CountDownLatch closed=new CountDownLatch(1);volatile int closeCode;public void onOpen(WebSocket socket){socket.request(1);}public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closeCode=code;closed.countDown();return null;}}
    boolean tablesExist(){return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='xl_beta_code'",Integer.class)==1;}
    @AfterEach void cleanup(){if(!isolatedDatabase)return;sockets.forEach(WebSocket::abort);if(tablesExist()){jdbc.update("DELETE FROM xl_beta_code");jdbc.update("DELETE FROM xl_beta_stage");}for(String id:users){jdbc.update("DELETE FROM xl_media_ref WHERE owner_type='post' AND owner_id IN (SELECT id FROM xl_post WHERE author_id=?)",id);jdbc.update("DELETE FROM xl_media_ref WHERE owner_type='user_avatar' AND owner_id=?",id);jdbc.update("DELETE FROM xl_post WHERE author_id=?",id);jdbc.update("UPDATE xl_user SET avatar_image_id=NULL WHERE id=?",id);jdbc.update("DELETE FROM xl_image_asset WHERE owner_user_id=?",id);jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",id);jdbc.update("DELETE FROM xl_user_preference WHERE user_id=?",id);jdbc.update("DELETE FROM xl_user WHERE id=?",id);}for(String id:admins){jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='admin' AND subject_id=?",id);jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?",id);}}
}
