package cn.xinkelink.notification;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.forum.ForumModels;
import cn.xinkelink.forum.ForumService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"db","auth"})
@Transactional
class NotificationServiceIT {
    @Autowired NotificationService notices;
    @Autowired ForumService forum;
    @Autowired JdbcTemplate jdbc;
    AuthPrincipal author,reader,third;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("xinkelink.auth.jwt.secret-base64",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }
    @BeforeEach void fixture(){author=user("作者");reader=user("读者");third=user("第三人");}

    @Test void reactionsExcludeSelfAndDoNotNotifyAgainAfterCancellation(){
        long post=post(false);
        forum.likePost(post,true,author);
        assertEquals(0L,raw(author,"reactions"));
        forum.likePost(post,true,reader);forum.likePost(post,true,reader);
        assertEquals(1L,raw(author,"reactions"));
        forum.likePost(post,false,reader);
        assertEquals(0L,raw(author,"reactions"));
        forum.likePost(post,true,reader);
        assertEquals(0L,raw(author,"reactions"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=?",Integer.class,author.subjectId()));
    }
    @Test void anonymousRepliesHideRealActorAndDeduplicateReceivers(){
        long post=post(true);
        Map<?,?> root=comment(post,reader,null,"根评论");
        assertEquals(1L,raw(author,"comments"));
        comment(post,author,root.get("id").toString(),"匿名作者的回复");
        var items=notices.list(null,false,null,20,reader).items();
        assertEquals(1,items.size());
        Map<?,?> actor=(Map<?,?>)items.getFirst().get("actor");
        Map<?,?> actorUser=(Map<?,?>)actor.get("user");
        assertNull(actorUser.get("id"));assertEquals(true,actorUser.get("isAnonymous"));
        assertFalse(actor.toString().contains("作者"));
        comment(post,third,root.get("id").toString(),"同时通知作者与被回复者");
        assertEquals(2L,raw(author,"comments"));assertEquals(2L,raw(reader,"comments"));
    }
    @Test void hiddenTargetsUseSafePlaceholderAndReadCannotCrossRecipients(){
        long post=post(false);forum.likePost(post,true,reader);
        var notice=notices.list(null,false,null,20,author).items().getFirst();
        long id=Long.parseLong(notice.get("id").toString());
        assertThrows(BusinessException.class,()->notices.read(id,reader));
        assertEquals(true,notices.read(id,author).get("changed"));
        assertEquals(false,notices.read(id,author).get("changed"));
        jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?",post);
        var hidden=notices.list(null,false,null,20,author).items().getFirst();
        assertNull(hidden.get("quote"));assertNull(hidden.get("image"));
        assertEquals("unavailable",((Map<?,?>)hidden.get("target")).get("availability"));
    }
    @Test void unreadRawCountsRemainWhilePreferencesOnlyAffectBadge(){
        long post=post(false);forum.likePost(post,true,reader);comment(post,reader,null,"评论");
        jdbc.update("INSERT INTO xl_user_preference(user_id,interactions) VALUES (?,0)",author.subjectId());
        var unread=notices.unread(author);
        assertEquals(2L,((Map<?,?>)unread.get("raw")).get("total"));
        assertEquals(0L,unread.get("badgeTotal"));
        assertEquals(2,notices.readAll(null,author).get("readCount"));
        assertEquals(0L,raw(author,"total"));
        assertEquals(0,notices.readAll(null,author).get("readCount"));
    }
    @Test void notificationCursorCannotMoveToAnotherRecipientOrFilter(){
        long post=post(false);forum.likePost(post,true,reader);forum.favorite(post,true,reader);
        var page=notices.list("reaction",false,null,1,author);
        assertTrue(page.hasMore());
        assertThrows(BusinessException.class,()->notices.list("comment",false,page.nextCursor(),1,author));
        assertThrows(BusinessException.class,()->notices.list("reaction",false,page.nextCursor(),1,reader));
        assertEquals(1,notices.list("reaction",false,page.nextCursor(),1,author).items().size());
    }
    @Test void moderationFeedbackIsPrivateAndIdempotent(){
        notices.moderation(author.subjectId(),"case:test","举报处理结果","已完成处理",null,null);
        notices.moderation(author.subjectId(),"case:test","举报处理结果","已完成处理",null,null);
        assertEquals(1,notices.list(null,false,null,20,author).items().size());
        assertTrue(notices.list(null,false,null,20,reader).items().isEmpty());
        assertNull(((Map<?,?>)notices.list(null,false,null,20,author).items().getFirst().get("actor")).get("user"));
    }
    private AuthPrincipal user(String name){String key=UUID.randomUUID().toString();jdbc.update(
            "INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES ('it-day4-notice',?,?)",key,name);
        long id=jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_open_id=?",Long.class,key);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",id);
        return new AuthPrincipal(id,jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",Long.class,id),"user");}
    private long post(boolean anonymous){return Long.parseLong(forum.create(new ForumModels.CreatePost("测试正文","study",List.of(),anonymous,UUID.randomUUID().toString()),author).get("id").toString());}
    private Map<?,?> comment(long post,AuthPrincipal who,String reply,String text){return (Map<?,?>)forum.createComment(post,new ForumModels.CreateComment(text,reply,UUID.randomUUID().toString()),who).get("comment");}
    private long raw(AuthPrincipal user,String key){return ((Number)((Map<?,?>)notices.unread(user).get("raw")).get(key)).longValue();}
}
