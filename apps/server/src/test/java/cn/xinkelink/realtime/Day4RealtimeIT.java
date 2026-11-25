package cn.xinkelink.realtime;

import cn.xinkelink.auth.AuthModels.UserTokens;
import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 实际TCP HTTP/WS与MySQL；测试身份从受信服务准备，不伪称微信换码已联调。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"db","auth"})
class Day4RealtimeIT {
    @Value("${local.server.port}") int port;
    @Autowired AuthService auth;
    @Autowired JwtTokenService jwt;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final List<Long> users=new ArrayList<>(),admins=new ArrayList<>();
    final List<WebSocket> sockets=new ArrayList<>();
    UserTokens author,reader,outsider;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        r.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(key));}
    @BeforeEach void fixture(){author=user();reader=user();outsider=user();}
    UserTokens user(){var user=auth.loginUser(new WechatIdentity("it-day4-wire",UUID.randomUUID().toString()));users.add(Long.parseLong(user.me().id()));return user;}

    @Test void handshakeRequiresUserBearerAndRejectsQueryTokensAndAdmin()throws Exception{
        assertHandshake(null,"",401);
        assertHandshake(null,"?token=not-a-credential",401);
        assertHandshake(author.accessToken(),"?userId="+reader.me().id(),403);
        assertHandshake(adminToken(),"",403);
        Listener accepted=new Listener();WebSocket socket=connect(reader.accessToken(),accepted);
        assertFalse(socket.isInputClosed());
        socket.sendText("{\"subscribe\":\"other-user\"}",true).join();
        assertTrue(accepted.closed.await(5,TimeUnit.SECONDS));
        assertEquals(1008,accepted.closeCode);
    }
    @Test void committedMessagesReachOnlyMembersAndHistoryWorksAfterSocketDisconnects()throws Exception{
        Listener a=new Listener(),b=new Listener(),c=new Listener();
        connect(author.accessToken(),a);WebSocket peerSocket=connect(reader.accessToken(),b);connect(outsider.accessToken(),c);
        String conversation=call("POST","/api/v1/conversations",Map.of("peerUserId",reader.me().id()),author.accessToken(),201).get("id").stringValue();
        String request=UUID.randomUUID().toString();
        JsonNode message=call("POST","/api/v1/conversations/"+conversation+"/messages",Map.of("text","真实WS持久消息","clientMessageId",request),author.accessToken(),201);
        String messageId=message.get("id").stringValue();
        JsonNode received=event(b,"message.created").get("payload");
        assertEquals(messageId,received.get("id").stringValue());assertFalse(received.get("isMine").booleanValue());
        assertTrue(event(a,"message.created").get("payload").get("isMine").booleanValue());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message WHERE id=?",Integer.class,messageId));
        assertEquals(1,call("GET","/api/v1/me/unread",null,reader.accessToken(),200).at("/raw/messages").intValue());
        call("GET","/api/v1/conversations/"+conversation+"/messages",null,outsider.accessToken(),404);
        call("POST","/api/v1/conversations/"+conversation+"/messages",Map.of("text","越权消息","clientMessageId",UUID.randomUUID().toString()),outsider.accessToken(),404);
        assertNull(c.events.poll(300,TimeUnit.MILLISECONDS));
        call("POST","/api/v1/conversations/"+conversation+"/messages",Map.of("text","真实WS持久消息","clientMessageId",request),author.accessToken(),201);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message WHERE conversation_id=?",Integer.class,conversation));
        call("PUT","/api/v1/conversations/"+conversation+"/read",Map.of("readUpToSequence","1"),reader.accessToken(),200);
        assertEquals("1",event(a,"read.updated").get("payload").get("readThroughSequence").stringValue());
        assertEquals("1",event(b,"read.updated").get("payload").get("readThroughSequence").stringValue());
        // 首次read还会向读者发送会话及未读更新；先验证并消费该次合法事件。
        JsonNode updatedConversation=event(b,"conversation.updated").get("payload");
        assertEquals(conversation,updatedConversation.get("id").stringValue());
        assertEquals(0,updatedConversation.get("unreadCount").intValue());
        assertEquals(0,event(b,"unread.updated").at("/payload/raw/messages").intValue());
        // 重复确认相同已读位置不得继续广播，否则聊天页收到自己的事件后会无限重载。
        call("PUT","/api/v1/conversations/"+conversation+"/read",Map.of("readUpToSequence","1"),reader.accessToken(),200);
        assertNull(a.events.poll(300,TimeUnit.MILLISECONDS));
        assertNull(b.events.poll(300,TimeUnit.MILLISECONDS));
        peerSocket.sendClose(1000,"test complete").join();assertTrue(b.closed.await(4,TimeUnit.SECONDS));
        call("POST","/api/v1/conversations/"+conversation+"/messages",Map.of("text","断线后HTTP仍可靠","clientMessageId",UUID.randomUUID().toString()),author.accessToken(),201);
        JsonNode history=call("GET","/api/v1/conversations/"+conversation+"/messages?afterSequence=1",null,reader.accessToken(),200);
        assertEquals(1,history.get("items").size());assertEquals("2",history.at("/items/0/sequence").stringValue());
    }
    @Test void logoutRevokesExistingSocketAndExpiredBanPreservesManualRestrictions()throws Exception{
        Listener listener=new Listener();connect(reader.accessToken(),listener);
        call("POST","/api/v1/auth/logout",Map.of(),reader.accessToken(),200);
        assertTrue(listener.closed.await(5,TimeUnit.SECONDS));assertEquals(1008,listener.closeCode);
        jdbc.update("UPDATE xl_user SET ban_state='temporary',ban_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE),restrict_publish=1 WHERE id=?",author.me().id());
        JsonNode me=call("GET","/api/v1/me",null,author.accessToken(),200);
        assertTrue(me.at("/restrictions/publish").booleanValue());
        assertEquals("none",jdbc.queryForObject("SELECT ban_state FROM xl_user WHERE id=?",String.class,author.me().id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_moderation_action WHERE target_user_id=? AND action='temporary_ban_expired'",Integer.class,author.me().id()));
        call("POST","/api/v1/posts",Map.of("content","限制后不能发布","category","study","imageIds",List.of(),"anonymous",false,"clientRequestId",UUID.randomUUID().toString()),author.accessToken(),403);
    }
    @Test void tokenExpiryAndAdministrativeBanCloseExistingConnections()throws Exception{
        var token=jwt.decode(author.accessToken());
        String shortLived=jwt.issue("user",Long.parseLong(author.me().id()),Long.parseLong(token.getClaimAsString("sid")),java.time.Instant.now().plusSeconds(3));
        Listener expiring=new Listener();connect(shortLived,expiring);
        assertTrue(expiring.closed.await(6,TimeUnit.SECONDS));assertEquals(1008,expiring.closeCode);
        Listener banned=new Listener();connect(reader.accessToken(),banned);
        long version=jdbc.queryForObject("SELECT version FROM xl_user WHERE id=?",Long.class,reader.me().id());
        call("POST","/api/v1/admin/users/"+reader.me().id()+"/ban",Map.of("mode","permanent","reason","测试封禁","expectedVersion",version,"confirmed",true),adminToken(),200);
        assertTrue(banned.closed.await(5,TimeUnit.SECONDS));assertEquals(1008,banned.closeCode);
        call("GET","/api/v1/me",null,reader.accessToken(),401);
    }
    @Test void realHttpNotificationsIsolateRecipientsAndKeepAnonymousActorsPrivate()throws Exception{
        Listener receiver=new Listener();connect(reader.accessToken(),receiver);
        String post=call("POST","/api/v1/posts",Map.of("content","匿名正文","category","study","imageIds",List.of(),"anonymous",true,"clientRequestId",UUID.randomUUID().toString()),author.accessToken(),201).get("id").stringValue();
        String comment=call("POST","/api/v1/posts/"+post+"/comments",Map.of("content","读者评论","clientRequestId",UUID.randomUUID().toString()),reader.accessToken(),201).at("/comment/id").stringValue();
        call("POST","/api/v1/posts/"+post+"/comments",Map.of("content","匿名作者回复","replyToCommentId",comment,"clientRequestId",UUID.randomUUID().toString()),author.accessToken(),201);
        JsonNode receipt=event(receiver,"notification.created").get("payload");
        assertTrue(receipt.at("/actor/user/isAnonymous").booleanValue());assertTrue(receipt.at("/actor/user/id").isNull());
        String notice=receipt.get("id").stringValue();
        call("PUT","/api/v1/notifications/"+notice+"/read",null,outsider.accessToken(),404);
        assertTrue(call("PUT","/api/v1/notifications/"+notice+"/read",null,reader.accessToken(),200).get("changed").booleanValue());
        assertFalse(call("PUT","/api/v1/notifications/"+notice+"/read",null,reader.accessToken(),200).get("changed").booleanValue());
        assertEquals(0,call("GET","/api/v1/me/unread",null,reader.accessToken(),200).at("/raw/comments").intValue());
    }
    private String adminToken(){String account="wire-"+UUID.randomUUID();jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES(?,?,'测试管理员',UTC_TIMESTAMP(3))",account,passwords.encode("test-only-password"));
        admins.add(jdbc.queryForObject("SELECT id FROM xl_admin WHERE login_name=?",Long.class,account));return auth.loginAdmin(account,"test-only-password").tokens().accessToken();}
    private WebSocket connect(String token,Listener listener){WebSocket socket=client.newWebSocketBuilder().header("Authorization","Bearer "+token).connectTimeout(Duration.ofSeconds(3))
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/v1/ws"),listener).join();sockets.add(socket);return socket;}
    private void assertHandshake(String token,String suffix,int status){var builder=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3));if(token!=null)builder.header("Authorization","Bearer "+token);
        CompletionException failed=assertThrows(CompletionException.class,()->builder.buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/v1/ws"+suffix),new Listener()).join());
        assertInstanceOf(WebSocketHandshakeException.class,failed.getCause());assertEquals(status,((WebSocketHandshakeException)failed.getCause()).getResponse().statusCode());}
    private JsonNode call(String method,String path,Object body,String token,int expected)throws Exception{
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json");
        var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),response.body());return json.readTree(response.body()).get("data");
    }
    private JsonNode event(Listener listener,String type)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){JsonNode event=listener.events.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&type.equals(event.get("type").stringValue()))return event;}
        return fail("未收到实际WS事件："+type);}
    class Listener implements WebSocket.Listener {
        final BlockingQueue<JsonNode> events=new LinkedBlockingQueue<>();final CountDownLatch closed=new CountDownLatch(1);final StringBuilder fragments=new StringBuilder();volatile int closeCode;
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){fragments.append(text);if(last){events.add(json.readTree(fragments.toString()));fragments.setLength(0);}socket.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket socket,int status,String reason){closeCode=status;closed.countDown();return null;}
        @Override public void onError(WebSocket socket,Throwable error){closed.countDown();}
    }
    @AfterEach void cleanup(){
        sockets.forEach(WebSocket::abort);
        for(long id:users){
            var conversations=jdbc.queryForList("SELECT id FROM xl_conversation WHERE low_user_id=? OR high_user_id=?",Long.class,id,id);
            for(long conversation:conversations){jdbc.update("DELETE FROM xl_message WHERE conversation_id=?",conversation);jdbc.update("DELETE FROM xl_conversation_member WHERE conversation_id=?",conversation);jdbc.update("DELETE FROM xl_conversation WHERE id=?",conversation);}
            jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id=? OR actor_id=?",id,id);
            jdbc.update("DELETE FROM xl_moderation_action WHERE target_user_id=?",id);
            jdbc.update("DELETE FROM xl_like WHERE user_id=?",id);jdbc.update("DELETE FROM xl_favorite WHERE user_id=?",id);
        }
        for(long id:users)jdbc.update("DELETE FROM xl_comment WHERE author_id=?",id);
        for(long id:users){jdbc.update("DELETE FROM xl_post WHERE author_id=?",id);jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",id);jdbc.update("DELETE FROM xl_user_preference WHERE user_id=?",id);jdbc.update("DELETE FROM xl_user WHERE id=?",id);}
        for(long id:admins){jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='admin' AND subject_id=?",id);jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?",id);}
    }
}
