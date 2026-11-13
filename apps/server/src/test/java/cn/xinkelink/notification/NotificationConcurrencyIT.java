package cn.xinkelink.notification;

import cn.xinkelink.auth.*;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.forum.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles({"db","auth"})
class NotificationConcurrencyIT {
    @Autowired ForumService forum;
    @Autowired AuthService auth;
    @Autowired JwtTokenService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ContentSupport support;
    AuthPrincipal first,second;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(new byte[32]));}
    @BeforeEach void users(){first=user();second=user();}
    @Test void mutualPostLikesCommitBothReceiptsWithoutRecipientForeignKeyDeadlock()throws Exception{
        long left=post(first),right=post(second);barrier(null);
        both(()->forum.likePost(right,true,first),()->forum.likePost(left,true,second));
        assertReceipts("post_like");
    }
    @Test void mutualCommentsCommitBothReceiptsWithoutRecipientForeignKeyDeadlock()throws Exception{
        long left=post(first),right=post(second);barrier("comment");
        both(()->forum.createComment(right,new ForumModels.CreateComment("双向评论",null,UUID.randomUUID().toString()),first),
                ()->forum.createComment(left,new ForumModels.CreateComment("双向评论",null,UUID.randomUUID().toString()),second));
        assertReceipts("comment");
    }
    private void barrier(String channel){CountDownLatch bothLocked=new CountDownLatch(2);
        doAnswer(invocation->{Object result=invocation.callRealMethod();AuthPrincipal actor=invocation.getArgument(0);
            if(actor!=null&&(actor.subjectId()==first.subjectId()||actor.subjectId()==second.subjectId())&&Objects.equals(channel,invocation.getArgument(1))){
                bothLocked.countDown();bothLocked.await(500,TimeUnit.MILLISECONDS);
            }return result;}).when(support).require(any(),nullable(String.class));
    }
    private void assertReceipts(String kind){for(AuthPrincipal actor:List.of(first,second))assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=? AND event_kind=?",Integer.class,actor.subjectId(),kind));}
    private void both(Callable<?> one,Callable<?> two)throws Exception{CountDownLatch go=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)){var a=workers.submit(()->{go.await();return one.call();});var b=workers.submit(()->{go.await();return two.call();});go.countDown();
            assertNotNull(a.get(15,TimeUnit.SECONDS));assertNotNull(b.get(15,TimeUnit.SECONDS));}}
    private AuthPrincipal user(){var tokens=auth.loginUser(new WechatIdentity("notice-concurrency-it",UUID.randomUUID().toString()));return auth.authenticate(jwt.decode(tokens.accessToken()));}
    private long post(AuthPrincipal actor){return Long.parseLong(forum.create(new ForumModels.CreatePost("互相互动测试","study",List.of(),false,UUID.randomUUID().toString()),actor).get("id").toString());}
    @AfterEach void cleanup(){for(AuthPrincipal actor:List.of(first,second))jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id=? OR actor_id=?",actor.subjectId(),actor.subjectId());
        for(AuthPrincipal actor:List.of(first,second)){long id=actor.subjectId();jdbc.update("DELETE FROM xl_like WHERE user_id=?",id);jdbc.update("DELETE FROM xl_favorite WHERE user_id=?",id);}
        for(AuthPrincipal actor:List.of(first,second))jdbc.update("DELETE FROM xl_comment WHERE author_id=?",actor.subjectId());
        for(AuthPrincipal actor:List.of(first,second)){long id=actor.subjectId();jdbc.update("DELETE FROM xl_post WHERE author_id=?",id);jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",id);jdbc.update("DELETE FROM xl_user WHERE id=?",id);}}
}
