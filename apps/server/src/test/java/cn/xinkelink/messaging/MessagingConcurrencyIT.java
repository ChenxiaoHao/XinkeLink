package cn.xinkelink.messaging;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.realtime.RealtimeEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles({"db","auth"})
class MessagingConcurrencyIT {

    @Autowired MessagingService messaging;
    @MockitoSpyBean MessagingStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired JwtTokenService jwt;
    @Autowired RealtimeEvents realtime;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;
    final List<Long> users=new ArrayList<>();
    final List<WebSocketSession> sockets=new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry){registry.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(new byte[32]));}

    @Test
    void concurrentOppositeOpensCommitExactlyOnePairWithTwoMembers()throws Exception {
        var a=user();var b=user();AtomicInteger turn=new AtomicInteger();
        var opened=concurrent(8,()->turn.getAndIncrement()%2==0?open(a,b):open(b,a));
        assertEquals(1,opened.stream().distinct().count());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_conversation_member WHERE conversation_id=?",Integer.class,opened.getFirst()));
    }

    @Test
    void concurrentSendersCommitConsecutiveSequencesAndRetriesAllocateOnlyOnce()throws Exception {
        var a=user();var b=user();long id=open(a,b);AtomicInteger turn=new AtomicInteger();
        var messages=concurrent(12,()->{
            int n=turn.incrementAndGet();return messaging.send(id,new MessagingModels.SendMessage("并发"+n,"distinct-"+n),n%2==0?a:b);
        });
        var sequences=messages.stream().map(message->Integer.parseInt(message.get("sequence").toString())).sorted().toList();
        assertEquals(List.of(1,2,3,4,5,6,7,8,9,10,11,12),sequences);
        var retry=new MessagingModels.SendMessage("重试相同正文","same-concurrent-key");
        var retried=concurrent(6,()->messaging.send(id,retry,a));
        assertEquals(1,retried.stream().map(message->message.get("id")).distinct().count());
        assertEquals(13,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message WHERE conversation_id=?",Integer.class,id));
        assertEquals("14",jdbc.queryForObject("SELECT next_sequence FROM xl_conversation WHERE id=?",String.class,id));
    }

    @Test
    void sameSenderRequestRacingAcrossTwoConversationsCommitsOnlyOnePayload()throws Exception {
        var a=user();var b=user();var c=user();long first=open(a,b),second=open(a,c);AtomicInteger turn=new AtomicInteger();
        var results=concurrent(2,()->{
            long id=turn.getAndIncrement()==0?first:second;
            try{messaging.send(id,new MessagingModels.SendMessage("同键不同会话","cross-conversation"),a);return "SENT";}
            catch(BusinessException failure){return failure.code().name();}
        });
        assertEquals(1,Collections.frequency(results,"SENT"));
        assertEquals(1,Collections.frequency(results,ErrorCode.DUPLICATE_REQUEST_CONFLICT.name()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message WHERE sender_id=?",Integer.class,a.subjectId()));
    }

    @Test
    void markAllReadUsesCapturedWatermarksWhenAnotherMessageCommitsBeforeUpdates()throws Exception {
        var a=user();var b=user();long id=open(a,b);
        messaging.send(id,new MessagingModels.SendMessage("捕获前","before-capture"),a);
        CountDownLatch captured=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(invocation->{
            Object actual=invocation.callRealMethod();captured.countDown();
            assertTrue(release.await(10,TimeUnit.SECONDS));return actual;
        }).when(store).capture(b.subjectId());
        try(var executor=Executors.newSingleThreadExecutor()){
            Future<Map<String,Object>> read=executor.submit(()->messaging.readAll(b));
            try{
                assertTrue(captured.await(10,TimeUnit.SECONDS));
                messaging.send(id,new MessagingModels.SendMessage("捕获后","after-capture"),a);
            }finally{release.countDown();}
            Map<String,Object> result=read.get(15,TimeUnit.SECONDS);
            assertEquals("1",((Map<?,?>)((List<?>)result.get("watermarks")).getFirst()).get("readThroughSequence"));
        }
        assertEquals("1",jdbc.queryForObject("SELECT read_through_seq FROM xl_conversation_member WHERE conversation_id=? AND user_id=?",String.class,id,b.subjectId()));
        assertEquals(1L,messaging.detail(id,b).get("unreadCount"));
    }

    @Test
    void rolledBackSendPublishesNothingAndCommittedEventsProjectEachRecipientsIdentity()throws Exception {
        var a=user();var b=user();var outsider=user();long id=open(a,b);
        var aEvents=connect(a);var bEvents=connect(b);var oEvents=connect(outsider);
        TransactionTemplate transaction=new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(status->{
            messaging.send(id,new MessagingModels.SendMessage("回滚","rollback"),a);
            assertTrue(aEvents.isEmpty());assertTrue(bEvents.isEmpty());throw new IllegalStateException("测试回滚");
        }));
        assertTrue(aEvents.isEmpty());assertTrue(bEvents.isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message WHERE conversation_id=?",Integer.class,id));
        transaction.executeWithoutResult(status->{
            messaging.send(id,new MessagingModels.SendMessage("提交","commit"),a);
            assertTrue(aEvents.isEmpty());assertTrue(bEvents.isEmpty());
        });
        assertEquals(true,payload(aEvents,"message.created").get("isMine"));
        assertEquals(false,payload(bEvents,"message.created").get("isMine"));
        assertEquals("1",payload(bEvents,"message.created").get("sequence"));
        assertEquals(1,((Map<?,?>)payload(bEvents,"unread.updated").get("raw")).get("messages"));
        assertTrue(oEvents.isEmpty());
    }

    private final Map<Long,String> tokens=new HashMap<>();
    AuthPrincipal user(){
        var login=auth.loginUser(new WechatIdentity("messaging-concurrency-it",UUID.randomUUID().toString()));
        AuthPrincipal actor=auth.authenticate(jwt.decode(login.accessToken()));users.add(actor.subjectId());tokens.put(actor.subjectId(),login.accessToken());return actor;
    }
    long open(AuthPrincipal actor,AuthPrincipal peer){return Long.parseLong(messaging.open(new MessagingModels.OpenConversation(Long.toString(peer.subjectId())),actor).get("id").toString());}
    List<Map<String,Object>> connect(AuthPrincipal actor)throws Exception {
        WebSocketSession socket=mock(WebSocketSession.class);String id=UUID.randomUUID().toString();
        Map<String,Object> attributes=new HashMap<>();attributes.put("actor",actor);attributes.put("bearer",tokens.get(actor.subjectId()));
        when(socket.getId()).thenReturn(id);when(socket.getAttributes()).thenReturn(attributes);when(socket.isOpen()).thenReturn(true);
        List<Map<String,Object>> received=new CopyOnWriteArrayList<>();
        doAnswer(invocation->{TextMessage message=invocation.getArgument(0);received.add(json.readValue(message.getPayload(),Map.class));return null;}).when(socket).sendMessage(any());
        realtime.afterConnectionEstablished(socket);sockets.add(socket);return received;
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> payload(List<Map<String,Object>> events,String type){return (Map<String,Object>)events.stream().filter(event->type.equals(event.get("type"))).findFirst().orElseThrow().get("payload");}
    static <T> List<T> concurrent(int count,Callable<T> task)throws Exception {
        CountDownLatch ready=new CountDownLatch(count),start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(count)){
            List<Future<T>> futures=new ArrayList<>();
            for(int i=0;i<count;i++)futures.add(executor.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return task.call();}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();List<T> results=new ArrayList<>();
            for(Future<T> future:futures)results.add(future.get(25,TimeUnit.SECONDS));return results;
        }
    }
    @AfterEach
    void cleanup()throws Exception {
        for(WebSocketSession socket:sockets)realtime.afterConnectionClosed(socket,CloseStatus.NORMAL);
        reset(store);
        for(long user:users){
            var ids=jdbc.queryForList("SELECT id FROM xl_conversation WHERE low_user_id=? OR high_user_id=?",Long.class,user,user);
            for(long id:ids){
                jdbc.update("DELETE FROM xl_message WHERE conversation_id=?",id);
                
                jdbc.update("DELETE FROM xl_conversation_member WHERE conversation_id=?",id);
                jdbc.update("DELETE FROM xl_conversation WHERE id=?",id);
            }
            jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",user);
            jdbc.update("DELETE FROM xl_user_preference WHERE user_id=?",user);
            jdbc.update("DELETE FROM xl_user_activity_day WHERE user_id=?",user);
            
            jdbc.update("DELETE FROM xl_user WHERE id=?",user);
        }
    }
}
