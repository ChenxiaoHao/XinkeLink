package cn.xinkelink.auth;

import cn.xinkelink.auth.wechat.WechatIdentity;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"db", "auth"})
class AuthConcurrencyIT {
    private static final String SECRET = randomSecret();
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> SECRET);
    }

    @Test
    void concurrentFirstLoginsShareOneUserAndCommitTheirOwnSessions() throws Exception {
        WechatIdentity identity = randomIdentity();
        try {
            // 不使用外层回滚事务，每个工作线程通过真实服务代理独立提交。
            List<AuthModels.UserTokens> logins = concurrently(4, () -> auth.loginUser(identity));

            assertEquals(1, logins.stream().map(tokens -> tokens.me().id()).distinct().count());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=? AND wechat_open_id=?
                    """, Integer.class, identity.appId(), identity.openId()));
            long userId = Long.parseLong(logins.getFirst().me().id());
            assertEquals(4, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM xl_auth_session WHERE subject_type='user' AND subject_id=?
                    """, Integer.class, userId));
        } finally {
            deleteFixture(identity);
        }
    }

    @Test
    void concurrentRefreshAcceptsOneTicketOnceAndKeepsTheOriginalDeadline() throws Exception {
        WechatIdentity identity = randomIdentity();
        try {
            AuthModels.UserTokens login = auth.loginUser(identity);
            long userId = Long.parseLong(login.me().id());
            long sessionId = jdbc.queryForObject("""
                    SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?
                    """, Long.class, userId);
            // 缩短测试会话的期限，使错误地重设为三十天能够稳定被发现。
            Timestamp deadline = Timestamp.from(Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS));
            assertEquals(1, jdbc.update("""
                    UPDATE xl_auth_session SET expires_at=?
                    WHERE id=? AND subject_type='user' AND subject_id=?
                    """, deadline, sessionId, userId));

            List<ErrorCode> results = concurrently(2, () -> {
                try {
                    AuthModels.UserTokens rotated = auth.refreshUser(login.refreshToken());
                    assertFalse(login.refreshToken().equals(rotated.refreshToken()), "成功刷新必须生成新票据");
                    return null;
                } catch (BusinessException failure) {
                    return failure.code();
                }
            });

            assertEquals(1, results.stream().filter(Objects::isNull).count());
            assertEquals(List.of(ErrorCode.REFRESH_INVALID), results.stream().filter(Objects::nonNull).toList());
            BusinessException replay = assertThrows(BusinessException.class,
                    () -> auth.refreshUser(login.refreshToken()));
            assertEquals(ErrorCode.REFRESH_INVALID, replay.code());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT refresh_generation FROM xl_auth_session
                    WHERE id=? AND subject_type='user' AND subject_id=?
                    """, Integer.class, sessionId, userId));
            assertEquals(deadline, jdbc.queryForObject("""
                    SELECT expires_at FROM xl_auth_session
                    WHERE id=? AND subject_type='user' AND subject_id=?
                    """, Timestamp.class, sessionId, userId));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM xl_auth_session WHERE subject_type='user' AND subject_id=?
                    """, Integer.class, userId));
        } finally {
            deleteFixture(identity);
        }
    }

    private static <T> List<T> concurrently(int workers, Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        // close 会等待全部线程结束，保证失败时也先结束工作线程再清理数据库。
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "并发起跑等待超时");
                    return action.call();
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未准备就绪");
                start.countDown();
                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
                return results;
            } finally {
                start.countDown();
            }
        }
    }

    private void deleteFixture(WechatIdentity identity) {
        // 先按本测试的完整随机身份取主键，再限定主体类型清理，绝不全表删除。
        List<Long> userIds = jdbc.queryForList("""
                SELECT id FROM xl_user WHERE wechat_app_id=? AND wechat_open_id=?
                """, Long.class, identity.appId(), identity.openId());
        for (long userId : userIds) {
            jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", userId);
            jdbc.update("DELETE FROM xl_user WHERE id=? AND wechat_app_id=? AND wechat_open_id=?",
                    userId, identity.appId(), identity.openId());
        }
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=? AND wechat_open_id=?
                """, Integer.class, identity.appId(), identity.openId()));
    }

    private static WechatIdentity randomIdentity() {
        return new WechatIdentity("itc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 28),
                "it-concurrency-" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
