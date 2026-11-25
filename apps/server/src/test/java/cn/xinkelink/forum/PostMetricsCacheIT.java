package cn.xinkelink.forum;

import cn.xinkelink.admin.AdminCommandService;
import cn.xinkelink.admin.AdminModels;
import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.forum.metrics.PostMetricsCache;
import cn.xinkelink.moderation.ModerationModels;
import cn.xinkelink.moderation.ReportModerationService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL/Redis regression. Deliberately has no test-managed transaction: cache reads
 * use separately committed read-only transactions, and writes exercise afterCommit hooks. */
@SpringBootTest
@ActiveProfiles({"db", "auth", "redis"})
@Import(PostMetricsCacheIT.SqlCountingConfiguration.class)
class PostMetricsCacheIT {
    @Autowired ForumService forum;
    @Autowired AdminCommandService adminCommands;
    @Autowired ReportModerationService reports;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PostMetricsCache cache;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MetricsSqlCounter sql;
    final List<Long> posts = new ArrayList<>();
    final List<Long> users = new ArrayList<>();
    final String marker = "metrics-" + UUID.randomUUID();
    AuthPrincipal author, reader, other, admin;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        // Validate before DataSource/Flyway initialization, so a wrong runner cannot migrate dev.
        registry.add("spring.datasource.url", PostMetricsCacheIT::isolatedDatabaseUrl);
        registry.add("spring.flyway.url", PostMetricsCacheIT::isolatedDatabaseUrl);
        registry.add("xinkelink.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("xinkelink.cache.post-metrics.enabled", () -> "true");
    }

    private static String isolatedDatabaseUrl() {
        String url = System.getenv("XINKELINK_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_[a-zA-Z0-9_]+_test(?:\\?.*)?"))
            throw new IllegalStateException("PostMetricsCacheIT requires the isolated cache IT environment loader");
        return url;
    }

    @BeforeEach void fixture() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            String url = connection.getMetaData().getURL();
            assertTrue(url.contains(":13306/xinkelink_cache_") && url.contains("_test"),
                    "Run only against the dedicated cache IT database on port 13306");
        }
        assertEquals("PONG", redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping()));
        enabled(true);
        ReflectionTestUtils.setField(cache, "l1Enabled", true);
        cache.onSubscriptionRestored();
        author = user(); reader = user(); other = user();
        String login = "metrics-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,'fixture','缓存回归',UTC_TIMESTAMP(3))", login);
        long adminId = jdbc.queryForObject("SELECT id FROM xl_admin WHERE login_name=?", Long.class, login);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('admin',?,UNHEX(SHA2(UUID(),256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))", adminId);
        long adminSession = jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='admin' AND subject_id=?", Long.class, adminId);
        admin = new AuthPrincipal(adminId, adminSession, "admin");
        sql.reset();
    }

    @AfterEach void cleanup() {
        enabled(true);
        ReflectionTestUtils.setField(cache, "l1Enabled", true);
        cache.onSubscriptionRestored();
        // Keep audit/notification history and unique fixture identities in the isolated IT DB.
        for (long id : posts) redis.delete(key(id));
        for (long id : posts) {
            jdbc.update("UPDATE xl_post SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE id=?", id);
        }
        if (admin != null) jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?", admin.subjectId());
    }

    @Test void firstMissFillsRedisAndSecondReadAvoidsPublicCountSql() {
        long id = post(false);
        sql.reset();
        var first = detail(id, null);
        assertEquals(1, sql.count());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(id))));
        Long ttl = redis.getExpire(key(id));
        assertNotNull(ttl); assertTrue(ttl > 0, "Real Redis entry must expire");
        assertEquals(first, detail(id, null));
        assertEquals(1, sql.count(), "A cache hit must skip the aggregate SQL");
    }

    @Test void redisTtlExpiresAndReadRefillsFromDatabase() throws Exception {
        ReflectionTestUtils.setField(cache, "l1Enabled", false);
        long id = post(false);
        detail(id, null);
        assertTrue(Boolean.TRUE.equals(redis.expire(key(id), Duration.ofMillis(80))));
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(key(id))) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))), "Redis must really expire the key");
        sql.reset();
        assertCounts(detail(id, null), 0, 0);
        assertEquals(1, sql.count());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(id))));
    }

    @Test void batchLoadsAllMissesInOneSqlAndOnlyLoadsNewMisses() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(post(false));
        sql.reset();
        assertEquals(8, read(() -> forum.favoriteItems(ids, null)).size());
        assertEquals(1, sql.count(), "Batch misses must not become one count query per post");
        assertEquals(new HashSet<>(ids), sql.lastIds);
        read(() -> forum.favoriteItems(ids, null));
        assertEquals(1, sql.count());
        assertEquals(8, read(() -> forum.list(null, "latest", marker, null, 50, null)).items().size());
        assertEquals(1, sql.count(), "List projection should reuse the same batch cache entries");
        long extra = post(false); ids.add(extra);
        sql.reset(); // post creation intentionally reads counts inside its write transaction
        read(() -> forum.favoriteItems(ids, null));
        assertEquals(1, sql.count());
        assertEquals(Set.of(extra), sql.lastIds);
    }

    @Test void guestAndTwoUsersShareOnlyCountsAndNeverIdentityOrViewerFlags() {
        long id = post(true);
        write(() -> forum.likePost(id, true, reader));
        write(() -> forum.favorite(id, true, reader));
        sql.reset();
        for (AuthPrincipal viewer : Arrays.asList(null, reader, other, author, null)) {
            var view = detail(id, viewer);
            assertCounts(view, 1, 0);
            assertEquals(Objects.equals(viewer, reader), view.get("liked"));
            assertEquals(Objects.equals(viewer, reader), view.get("favorited"));
            assertEquals(Objects.equals(viewer, author), view.get("isMine"));
            Map<?, ?> identity = (Map<?, ?>) view.get("author");
            assertNull(identity.get("id"));
            assertEquals(true, identity.get("isAnonymous"));
        }
        assertEquals(1, sql.count());
    }

    @Test void warmCacheDoesNotBypassHiddenDeletedOrAnonymousPermissions() {
        long id = post(true);
        detail(id, null);
        // Change DB directly to leave a warm, deliberately stale public count entry.
        jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?", id);
        assertThrows(BusinessException.class, () -> detail(id, null));
        assertThrows(BusinessException.class, () -> detail(id, reader));
        assertTrue(read(() -> forum.favoriteItems(List.of(id), reader)).isEmpty());
        assertEquals("hidden", detail(id, author).get("availability"));
        jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?", id);
        assertThrows(BusinessException.class, () -> detail(id, reader));
        assertTrue(read(() -> forum.favoriteItems(List.of(id), null)).isEmpty());
    }

    @Test void warmCacheStillChecksRealViewerSessionAndWriteRestrictions() {
        long id = post(false);
        detail(id, reader);
        jdbc.update("UPDATE xl_user SET restrict_publish=1 WHERE id=?", author.subjectId());
        assertThrows(BusinessException.class, () -> write(() -> forum.update(id,
                new ForumModels.UpdatePost("restricted", null, null, 0L), author)));
        jdbc.update("UPDATE xl_auth_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?", reader.sessionId());
        assertThrows(BusinessException.class, () -> detail(id, reader));
        assertCounts(detail(id, null), 0, 0);
    }

    @Test void writesInvalidateOnlyAfterCommitAndRefreshLikeAndCommentCounts() {
        long id = post(false);
        detail(id, null);
        write(() -> {
            forum.likePost(id, true, reader);
            assertTrue(Boolean.TRUE.equals(redis.hasKey(key(id))), "Invalidation must wait for commit");
            return null;
        });
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertCounts(detail(id, null), 1, 0);
        long root = comment(id, null, reader);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertCounts(detail(id, null), 1, 1);
        long reply = comment(id, root, author);
        assertCounts(detail(id, null), 1, 2);
        write(() -> forum.deleteComment(reply, 0, author));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertCounts(detail(id, null), 1, 1);
        write(() -> forum.likePost(id, false, reader));
        assertCounts(detail(id, null), 0, 1);
    }

    @Test void rollbackPreservesWarmCacheAndDoesNotPublishUncommittedCounts() {
        long id = post(false);
        detail(id, null);
        String before = redis.opsForValue().get(key(id));
        assertThrows(IllegalStateException.class, () -> write(() -> {
            forum.likePost(id, true, reader);
            assertCounts(forum.detail(id, reader), 1, 0); // write transaction bypasses cache
            assertEquals(before, redis.opsForValue().get(key(id)));
            throw new IllegalStateException("intentional rollback");
        }));
        assertEquals(before, redis.opsForValue().get(key(id)));
        sql.reset();
        assertCounts(detail(id, reader), 0, 0);
        assertEquals(0, sql.count(), "Rollback should preserve the previously committed cache entry");
        redis.delete(key(id));
        assertThrows(IllegalStateException.class, () -> write(() -> {
            forum.likePost(id, true, reader);
            assertCounts(forum.detail(id, reader), 1, 0);
            assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))), "A write transaction must not fill a cold cache");
            throw new IllegalStateException("intentional rollback");
        }));
        assertCounts(detail(id, null), 0, 0);
    }

    @Test void adminRootHideAndRestoreInvalidatesCountsIncludingReplies() {
        long id = post(false);
        long root = comment(id, null, reader);
        comment(id, root, author);
        comment(id, null, other);
        assertCounts(detail(id, null), 0, 3);
        write(() -> adminCommands.commentStatus(batch(root, 0, "hidden"), admin));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertCounts(detail(id, null), 0, 1);
        write(() -> adminCommands.commentStatus(batch(root, 1, "visible"), admin));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertCounts(detail(id, null), 0, 3);
        write(() -> forum.deleteComment(root, 2, reader));
        assertCounts(detail(id, null), 0, 1);
    }

    @Test void reportResolutionHideInvalidatesRootAndAdminPostRestoreKeepsPermissions() {
        long id = post(true);
        long root = comment(id, null, author);
        comment(id, root, reader);
        assertCounts(detail(id, null), 0, 2);
        long report = Long.parseLong(write(() -> reports.create(new ModerationModels.CreateReport(
                "comment", Long.toString(root), "other", "缓存集成回归", List.of(), UUID.randomUUID().toString()), reader)).get("id").toString());
        write(() -> reports.resolve(report, new ModerationModels.Resolution("hide_comment", "确认隐藏", 0L, true), admin));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertCounts(detail(id, null), 0, 0);
        write(() -> adminCommands.postStatus(batch(id, 0, "hidden"), admin));
        assertThrows(BusinessException.class, () -> detail(id, reader));
        assertEquals("hidden", detail(id, author).get("availability"));
        write(() -> adminCommands.postStatus(batch(id, 1, "visible"), admin));
        assertCounts(detail(id, null), 0, 0);
        assertNull(((Map<?, ?>) detail(id, null).get("author")).get("id"));
    }

    @Test void adminDeletePostInvalidatesWarmCountsAndRevokesPublicAccess() {
        long id = post(true);
        write(() -> forum.likePost(id, true, reader));
        long root = comment(id, null, reader);
        comment(id, root, author);
        assertCounts(detail(id, null), 1, 2);
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(id))));

        var deleted = write(() -> adminCommands.deletePost(id, 0, admin));
        assertEquals(2, deleted.get("removedCommentCount"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertThrows(BusinessException.class, () -> detail(id, null));
        assertThrows(BusinessException.class, () -> detail(id, reader));
        assertTrue(read(() -> forum.favoriteItems(List.of(id), reader)).isEmpty());
        var ownerView = detail(id, author);
        assertEquals("deleted", ownerView.get("availability"));
        assertCounts(ownerView, 1, 0);
        assertEquals(false, ((Map<?, ?>) ownerView.get("permissions")).get("canComment"));
        assertEquals(false, ((Map<?, ?>) ownerView.get("permissions")).get("canDelete"));
    }

    @Test void adminDeleteCommentsInvalidatesRootAndRepliesWithoutRemovingOtherRoots() {
        long id = post(false);
        long root = comment(id, null, reader);
        comment(id, root, author);
        comment(id, root, other);
        comment(id, null, author);
        assertCounts(detail(id, null), 0, 4);
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(id))));

        var deleted = write(() -> adminCommands.deleteComments(batch(root, 0, null), admin));
        assertEquals(3, deleted.get("removedCount"));
        assertEquals(2, deleted.get("removedReplyCount"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertCounts(detail(id, null), 0, 1);
        assertEquals(1, read(() -> forum.comments(id, null, 20, null)).items().size());
        assertThrows(BusinessException.class, () -> read(() -> forum.replies(root, null, 20, null)));
    }

    @Test void reportHidePostInvalidatesWarmCountsAndRestorationRechecksVisibility() {
        long id = post(true);
        long root = comment(id, null, author);
        comment(id, root, reader);
        assertCounts(detail(id, null), 0, 2);
        long report = Long.parseLong(write(() -> reports.create(new ModerationModels.CreateReport(
                "post", Long.toString(id), "other", "帖子隐藏缓存回归", List.of(), UUID.randomUUID().toString()), reader)).get("id").toString());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(id))));

        write(() -> reports.resolve(report, new ModerationModels.Resolution("hide_post", "确认隐藏帖子", 0L, true), admin));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertThrows(BusinessException.class, () -> detail(id, null));
        assertThrows(BusinessException.class, () -> detail(id, reader));
        assertTrue(read(() -> forum.favoriteItems(List.of(id), reader)).isEmpty());
        var ownerView = detail(id, author);
        assertEquals("hidden", ownerView.get("availability"));
        assertCounts(ownerView, 0, 0);
        assertEquals(false, ((Map<?, ?>) ownerView.get("permissions")).get("canComment"));
        assertNull(((Map<?, ?>) ownerView.get("author")).get("id"));
        write(() -> adminCommands.postStatus(batch(id, 1, "visible"), admin));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        assertCounts(detail(id, null), 0, 2);
    }

    @Test void enabledAndDisabledProduceEqualResultsWithMeasuredSqlCounts() {
        long id = post(false);
        write(() -> forum.likePost(id, true, reader));
        comment(id, null, other);
        enabled(false);
        var disabled = detail(id, reader);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(id))));
        enabled(true);
        ReflectionTestUtils.setField(cache, "l1Enabled", true);
        cache.onSubscriptionRestored();
        assertEquals(disabled, detail(id, reader));
        var expectedList = read(() -> forum.list(null, "latest", marker, null, 50, reader));
        for (String endpoint : List.of("detail", "list")) {
            for (boolean useCache : List.of(false, true)) {
                enabled(useCache); cache.onSubscriptionRestored(); redis.delete(key(id)); sql.reset();
                long[] nanos = new long[30];
                for (int i = 0; i < nanos.length; i++) {
                    long start = System.nanoTime();
                    if (endpoint.equals("detail")) assertEquals(disabled, detail(id, reader));
                    else assertEquals(expectedList, read(() -> forum.list(null, "latest", marker, null, 50, reader)));
                    nanos[i] = System.nanoTime() - start;
                }
                assertEquals(useCache ? 1 : 30, sql.count());
                Arrays.sort(nanos);
                System.out.printf(Locale.ROOT, "POST_METRICS_OBSERVATION endpoint=%s enabled=%s samples=30 aggregateSql=%d medianMs=%.3f p95Ms=%.3f%n",
                        endpoint, useCache, sql.count(), nanos[14] / 1_000_000.0, nanos[28] / 1_000_000.0);
            }
        }
    }

    private AuthPrincipal user() {
        String open = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('metrics-cache-it',?,'缓存回归用户')", open);
        long id = jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='metrics-cache-it' AND wechat_open_id=?", Long.class, open);
        users.add(id);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))", id);
        long session = jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", Long.class, id);
        return new AuthPrincipal(id, session, "user");
    }
    private long post(boolean anonymous) {
        long id = Long.parseLong(write(() -> forum.create(new ForumModels.CreatePost(marker, "campus", List.of(), anonymous, UUID.randomUUID().toString()), author)).get("id").toString());
        posts.add(id); redis.delete(key(id)); return id;
    }
    private long comment(long post, Long root, AuthPrincipal viewer) {
        var result = write(() -> forum.createComment(post, new ForumModels.CreateComment("回归评论", root == null ? null : root.toString(), UUID.randomUUID().toString()), viewer));
        return Long.parseLong(((Map<?, ?>) result.get("comment")).get("id").toString());
    }
    private AdminModels.Batch batch(long id, long version, String status) {
        return new AdminModels.Batch(List.of(new AdminModels.Item(Long.toString(id), version)), status, "缓存回归", true);
    }
    private Map<String, Object> detail(long id, AuthPrincipal viewer) { return read(() -> forum.detail(id, viewer)); }
    private <T> T read(Supplier<T> action) {
        var tx = new TransactionTemplate(transactions); tx.setReadOnly(true); return tx.execute(status -> action.get());
    }
    private <T> T write(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    private void enabled(boolean value) { ReflectionTestUtils.setField(cache, "enabled", value); }
    private static String key(long id) { return "xinkelink:cache:v3:post-metrics:" + id; }
    private static void assertCounts(Map<String, Object> view, long likes, long comments) {
        assertEquals(likes, ((Number) view.get("likeCount")).longValue());
        assertEquals(comments, ((Number) view.get("commentCount")).longValue());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCountingConfiguration {
        @Bean MetricsSqlCounter metricsSqlCounter() { return new MetricsSqlCounter(); }
    }
    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class MetricsSqlCounter implements Interceptor {
        private final AtomicInteger calls = new AtomicInteger();
        Set<Long> lastIds = Set.of();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (statement.equals(ForumMapper.class.getName() + ".publicPostMetrics")) {
                calls.incrementAndGet();
                Object parameter = invocation.getArgs()[1];
                if (parameter instanceof Map<?, ?> map && map.get("ids") instanceof Collection<?> ids) {
                    Set<Long> captured = new HashSet<>();
                    ids.forEach(id -> captured.add(((Number) id).longValue())); lastIds = captured;
                }
            }
            return invocation.proceed();
        }
        int count() { return calls.get(); }
        void reset() { calls.set(0); lastIds = Set.of(); }
    }
}
