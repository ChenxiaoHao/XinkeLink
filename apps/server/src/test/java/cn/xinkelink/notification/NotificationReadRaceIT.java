package cn.xinkelink.notification;

import cn.xinkelink.auth.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** 真实MySQL交错：标读已找到通知后，收藏者并发取消其自然事件。 */
@SpringBootTest
@ActiveProfiles({"db", "auth"})
class NotificationReadRaceIT {
    @Autowired NotificationService notices;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean NotificationMapper mapper;
    @Autowired cn.xinkelink.forum.ForumService forum;
    AuthPrincipal seller, buyer;
    long post, notice;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @BeforeEach
    void fixture() {
        seller = user("卖家");
        buyer = user("收藏者");
        String request = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO xl_post(author_id,content,category,client_request_id,request_hash)
                VALUES (?,'并发标读帖子','study',?,UNHEX(SHA2('fixture',256)))
                """, seller.subjectId(), request);
        post = jdbc.queryForObject("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?",
                Long.class, seller.subjectId(), request);
        forum.favorite(post, true, buyer);
        notice = jdbc.queryForObject("SELECT id FROM xl_user_notification WHERE recipient_id=? AND target_type='post' AND target_id=?",
                Long.class, seller.subjectId(), post);
    }

    @Test
    void readingAndCancellingPostFavoriteCompleteWithoutLosingTheReadResult() throws Exception {
        CountDownLatch beforeRead = new CountDownLatch(1);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        // Spring对MyBatis接口代理使用委托spy，保留真实SQL调用而非调用抽象接口方法。
        var realMapper = mockingDetails(mapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            beforeRead.countDown();
            assertTrue(cancellationStarted.await(5, TimeUnit.SECONDS), "并发取消收藏应已启动");
            // 修复后取消会等待通知行锁；这里只等500ms，随后让标读继续提交。
            cancellationFinished.await(500, TimeUnit.MILLISECONDS);
            return realMapper.answer(invocation);
        }).when(mapper).read(eq(notice), eq(seller.subjectId()));

        var workers = Executors.newFixedThreadPool(2);
        try {
            var reading = workers.submit(() -> notices.read(notice, seller));
            assertTrue(beforeRead.await(5, TimeUnit.SECONDS), "标读应已找到有效通知");
            var cancellation = workers.submit(() -> {
                cancellationStarted.countDown();
                try { return forum.favorite(post, false, buyer); }
                finally { cancellationFinished.countDown(); }
            });
            Map<String,Object> result = assertDoesNotThrow(() -> reading.get(10, TimeUnit.SECONDS),
                    "标读和取消收藏应可串行完成，不应因通知失活抛出异常");
            assertEquals(true, result.get("changed"));
            assertNotNull(result.get("readAt"));
            assertEquals(true, cancellation.get(10, TimeUnit.SECONDS).get("changed"));
            assertEquals(0, jdbc.queryForObject("SELECT active FROM xl_user_notification WHERE id=?", Integer.class, notice));
            assertNotNull(jdbc.queryForObject("SELECT read_at FROM xl_user_notification WHERE id=?", java.sql.Timestamp.class, notice));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM xl_favorite WHERE user_id=? AND target_type='post' AND target_id=?",
                    Integer.class, buyer.subjectId(), post));
            assertTrue(notices.list(null, false, null, 20, seller).items().isEmpty());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "并发事务应已结束");
        }
    }

    private AuthPrincipal user(String nickname) {
        String identity = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES ('notice-read-race-it',?,?)", identity, nickname);
        long id = jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='notice-read-race-it' AND wechat_open_id=?", Long.class, identity);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES ('user',?,UNHEX(SHA2(?,256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))", id, identity);
        long session = jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", Long.class, id);
        return new AuthPrincipal(id, session, "user");
    }

    @AfterEach
    void cleanup() {
        if (seller == null) return;
        jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id=?", seller.subjectId());
        jdbc.update("DELETE FROM xl_favorite WHERE target_type='post' AND target_id=?", post);
        
        jdbc.update("DELETE FROM xl_post WHERE id=? AND author_id=?",post,seller.subjectId());
        for (AuthPrincipal actor : List.of(seller, buyer)) {
            jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", actor.subjectId());
            jdbc.update("DELETE FROM xl_user WHERE id=?", actor.subjectId());
        }
    }
}
