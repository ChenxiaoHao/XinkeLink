package cn.xinkelink.content;

import cn.xinkelink.auth.AuthModels.UserTokens;
import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import cn.xinkelink.storage.ImageStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 不替换Redis/HTTP边界：仅用受信微信身份准备测试Token，业务请求走真实端口和MySQL。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"db","auth"})
class Day3HttpIT {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cn.xinkelink.beta.BetaService platformPhase;
    // Business regressions run in the public phase; beta access has its own integration suite.
    @org.junit.jupiter.api.BeforeEach void publicPlatformFixture(){org.mockito.Mockito.doReturn(false).when(platformPhase).isBeta();}

    @Value("${local.server.port}") int port;
    @Autowired AuthService auth;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStorage storage;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping routes;
    private final HttpClient client=HttpClient.newHttpClient();
    private final List<Long> actors=new ArrayList<>();
    UserTokens author,reader;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        String secret=Base64.getEncoder().encodeToString(key);
        registry.add("xinkelink.auth.jwt.secret-base64",()->secret);
        registry.add("spring.data.redis.port",()->16379);
    }
    @BeforeEach void fixture(){author=actor();reader=actor();}
    private UserTokens actor(){
        UserTokens user=auth.loginUser(new WechatIdentity("it-day3-wire",UUID.randomUUID().toString()));
        actors.add(Long.parseLong(user.me().id()));return user;
    }

    @Test void anonymousPostCommentsInteractionsEditingAndDeletionStayConsistent()throws Exception{
        JsonNode created=data(call("POST","/api/v1/posts",ContentSupport.map("content","学习记录","category","study",
                "anonymous",true,"imageIds",List.of(),"clientRequestId",UUID.randomUUID().toString()),author),201);
        String id=created.get("id").stringValue(),path="/api/v1/posts/"+id;
        JsonNode publicPost=data(call("GET",path,null,null),200);
        assertTrue(publicPost.at("/author/id").isNull());
        assertTrue(publicPost.at("/author/isAnonymous").booleanValue());
        JsonNode root=data(call("POST",path+"/comments",Map.of("content","一级评论","clientRequestId",UUID.randomUUID().toString()),reader),201).get("comment");
        String rootId=root.get("id").stringValue();
        JsonNode reply=data(call("POST",path+"/comments",Map.of("content","作者回复","replyToCommentId",rootId,
                "clientRequestId",UUID.randomUUID().toString()),author),201).get("comment");
        assertTrue(reply.at("/author/id").isNull());
        assertEquals(rootId,reply.get("rootId").stringValue());
        assertEquals(1,data(call("GET",path+"/comments",null,null),200).at("/items/0/replyCount").intValue());
        body(call("PATCH",path,Map.of("content","他人不能改","expectedVersion",0),reader),403);
        JsonNode edited=data(call("PATCH",path,Map.of("content","修改后的学习记录","expectedVersion",0),author),200);
        assertEquals(1,edited.get("version").intValue());
        assertTrue(data(call("PUT",path+"/like",null,reader),200).get("changed").booleanValue());
        assertFalse(data(call("PUT",path+"/like",null,reader),200).get("changed").booleanValue());
        data(call("PUT",path+"/favorite",null,reader),200);
        JsonNode favorites=data(call("GET","/api/v1/me/favorites?type=post",null,reader),200);
        assertEquals(id,favorites.at("/items/0/targetId").stringValue());
        assertTrue(favorites.at("/items/0/item/author/id").isNull());
        assertEquals(0,data(call("GET","/api/v1/users/"+author.me().id()+"/posts",null,null),200).get("items").size());
        body(call("DELETE",path,null,reader,"If-Match","\"1\""),403);
        JsonNode removed=data(call("DELETE","/api/v1/comments/"+rootId,null,reader,"If-Match","\"0\""),200);
        assertEquals(2,removed.get("removedCount").intValue());
        assertEquals(0,removed.get("postCommentCount").intValue());
        body(call("DELETE",path,null,author),428);
        data(call("DELETE",path,null,author,"If-Match","\"1\""),200);
        body(call("GET",path,null,null),404);
        assertEquals("unavailable",data(call("GET","/api/v1/me/favorites?type=post",null,reader),200).at("/items/0/availability").stringValue());
        data(call("DELETE",path+"/favorite",null,reader),200);
    }

    @Test void missingInputsAndAnonymousProtectedRequestsHaveCorrectErrors()throws Exception{
        body(call("GET","/api/v1/search?type=posts",null,null),400);
        body(upload("post",false),400);
        body(call("GET","/api/v1/me/posts",null,null),401);
        body(call("POST","/api/v1/posts",Map.of(),null),401);
        body(call("PUT","/api/v1/posts/1/like",null,null),401);
        body(call("GET","/api/v1/posts",null,null,"Authorization","Bearer invalid.jwt.value"),401);
        JsonNode valid=data(call("GET","/api/v1/posts",null,null),200);
        assertTrue(valid.get("items").isArray());
    }

    @Test void registersCurrentContractsWithoutPublicAdministratorAccess(){
        Map<String,Integer> counts=new HashMap<>();
        routes.getHandlerMethods().forEach((route,handler)->{
            if(route.getPatternValues().stream().anyMatch(path->path.startsWith("/api/v1/")))
                counts.merge(handler.getBeanType().getPackageName(),route.getMethodsCondition().getMethods().size(),Integer::sum);
        });
        assertEquals(16,counts.get("cn.xinkelink.forum"));
        assertFalse(counts.containsKey("cn.xinkelink.market"));
        assertEquals(4,counts.get("cn.xinkelink.media"));
        assertEquals(6,counts.get("cn.xinkelink.content"));
        assertEquals(8,counts.get("cn.xinkelink.auth"));
        assertEquals(10,counts.get("cn.xinkelink.messaging"));
        assertEquals(7,counts.get("cn.xinkelink.moderation"));
        assertEquals(15,counts.get("cn.xinkelink.operations"));
        assertEquals(4,counts.get("cn.xinkelink.notification"));
        assertEquals(15,counts.get("cn.xinkelink.admin"));
        assertEquals(5,counts.get("cn.xinkelink.profile"));
        assertEquals(3,counts.get("cn.xinkelink.audit"));
        assertEquals(3,counts.get("cn.xinkelink.settings"));
        assertEquals(5,counts.get("cn.xinkelink.beta"));
        assertEquals(104,counts.values().stream().mapToInt(Integer::intValue).sum());
        Set<String> day5=Set.of("/api/v1/admin/posts","/api/v1/admin/comments",
                "/api/v1/admin/users","/api/v1/admin/dashboard","/api/v1/admin/settings","/api/v1/admin/audit-events");
        assertTrue(day5.stream().allMatch(path->routes.getHandlerMethods().keySet().stream().anyMatch(route->route.getPatternValues().contains(path))));
    }

    @Test void everyDocumentedHttpMethodHasAnActualControllerMapping() throws Exception {
        var expected=new java.util.TreeSet<String>();
        String contract=java.nio.file.Files.readString(java.nio.file.Path.of("../../docs/backend/04-api-contract.md"));
        var matcher=java.util.regex.Pattern.compile("(?m)^\\| [A-Z]+[0-9]{2} \\| (GET|POST|PUT|PATCH|DELETE) \\| ([^ |]+)").matcher(contract);
        while(matcher.find())expected.add(matcher.group(1)+" "+matcher.group(2).replaceAll("\\{[^}]+}","{}"));
        var actual=new java.util.TreeSet<String>();
        routes.getHandlerMethods().forEach((route,handler)->route.getPatternValues().forEach(path->
                route.getMethodsCondition().getMethods().forEach(method->actual.add(method.name()+" "+path.replaceAll("\\{[^}]+}","{}")))));
        // Actuator使用独立的HandlerMapping，不能误判为Controller缺失。
        assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        actual.add("GET /actuator/health");
        expected.removeAll(actual);assertEquals(java.util.Set.of(),expected,"文档存在尚未注册的HTTP方法");
    }

    private HttpResponse<String> upload(String purpose,boolean includeFile)throws Exception{
        String boundary="xinkelink-"+UUID.randomUUID();
        ByteArrayOutputStream form=new ByteArrayOutputStream();
        form.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"+purpose+"\r\n").getBytes(StandardCharsets.UTF_8));
        if(includeFile){
            form.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"camera.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            ImageIO.write(new BufferedImage(8,6,BufferedImage.TYPE_INT_RGB),"png",form);
            form.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        form.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create(base()+"/api/v1/images"))
                .header("Authorization","Bearer "+author.accessToken()).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(form.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> call(String method,String path,Object payload,UserTokens actor,String...headers)throws Exception{
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base()+path)).header("Content-Type","application/json");
        if(actor!=null)request.header("Authorization","Bearer "+actor.accessToken());
        for(int i=0;i<headers.length;i+=2)request.header(headers[i],headers[i+1]);
        return client.send(request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response,int status){
        assertEquals(status,response.statusCode());
        JsonNode result=json.readTree(response.body());assertTrue(result.get("requestId").isString());return result;
    }
    private JsonNode data(HttpResponse<String> response,int status){return body(response,status).get("data");}
    private String base(){return "http://127.0.0.1:"+port;}

    @AfterEach void cleanup()throws Exception{
        // 仅删除本测试新建随机身份的内容和文件；HTTP工作线程不参加测试事务。
        for(long user:actors){
            jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id=? OR actor_id=?",user,user);
            for(long post:jdbc.queryForList("SELECT id FROM xl_post WHERE author_id=?",Long.class,user)){
                for(long comment:jdbc.queryForList("SELECT id FROM xl_comment WHERE target_type='post' AND target_id=?",Long.class,post))
                    jdbc.update("DELETE FROM xl_like WHERE target_type='comment' AND target_id=?",comment);
                jdbc.update("DELETE FROM xl_comment WHERE target_type='post' AND target_id=? AND root_id IS NOT NULL ORDER BY id DESC",post);
                jdbc.update("DELETE FROM xl_comment WHERE target_type='post' AND target_id=?",post);
                jdbc.update("DELETE FROM xl_like WHERE target_type='post' AND target_id=?",post);
                jdbc.update("DELETE FROM xl_favorite WHERE target_type='post' AND target_id=?",post);
                jdbc.update("DELETE FROM xl_media_ref WHERE owner_type='post' AND owner_id=?",post);
            }

            jdbc.update("DELETE FROM xl_post WHERE author_id=?",user);
            
            for(String key:jdbc.queryForList("SELECT storage_key FROM xl_image_asset WHERE owner_user_id=?",String.class,user))storage.delete(key);
            jdbc.update("DELETE FROM xl_image_asset WHERE owner_user_id=?",user);
            jdbc.update("DELETE FROM xl_favorite WHERE user_id=?",user);
            jdbc.update("DELETE FROM xl_like WHERE user_id=?",user);
            jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",user);
            jdbc.update("DELETE FROM xl_user WHERE id=? AND wechat_app_id='it-day3-wire'",user);
        }
    }
}
