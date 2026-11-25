package cn.xinkelink.statistics;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.forum.ForumService;
import cn.xinkelink.forum.ForumModels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;

import static org.junit.jupiter.api.Assertions.*;

/** Committed, uniquely named aggregate fixtures only in the protected named isolation database.
 * No runtime DELETE grants or Redis FLUSH are needed; evidence remains for reconciliation. */
@SpringBootTest
@ActiveProfiles({"db", "auth", "redis"})
class ViewProjectionIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ForumService forum;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", ViewProjectionIT::isolatedDatabase);
        properties.add("spring.flyway.url", ViewProjectionIT::isolatedDatabase);
    }
    private static String isolatedDatabase() {
        String url = System.getenv("XINKELINK_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_(?:cache|kafka)_[a-zA-Z0-9_]+_test(?:\\?.*)?"))
            throw new IllegalStateException("ViewProjectionIT requires the named isolated 13306 database");
        return url;
    }

    @Test void exactWindowCountsCrossDayVisitorOnceAndExpiresWithoutAnyNewEvent() {
        long post = newPost();
        Clock now = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
        String namespace = namespace();
        var projection = projection(namespace, now);
        daily(post, "2026-11-09", 50, "old");
        daily(post, "2026-11-11", 3, "shared", "second");
        daily(post, "2026-11-16", 4, "shared");
        assertTrue(projection.project());
        var entry = projection.read().entries().stream().filter(item -> item.postId() == post).findFirst().orElseThrow();
        assertEquals(7, entry.pv()); assertEquals(2, entry.uv());
        var expired = projection(namespace, Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC));
        assertFalse(expired.read().available());
        assertTrue(expired.project());
        assertTrue(expired.read().entries().stream().noneMatch(item -> item.postId() == post));
    }

    @Test void completeRedisNamespaceLossRebuildsAndDelayedOldTaskCannotOverwrite() {
        long post = newPost();
        Clock now = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
        String namespace = namespace();
        var projection = projection(namespace, now);
        daily(post, "2026-11-16", 1, "visitor");
        var old = projection.capture();
        assertTrue(projection.project());
        var expected = projection.read().entries();
        redis.delete(List.of("xl:view:{" + namespace + "}:ranking", "xl:view:{" + namespace + "}:generation"));
        assertFalse(projection.read().available());
        assertFalse(projection.publish(old));
        assertTrue(projection.project());
        assertEquals(expected, projection.read().entries());
    }

    @Test void concurrentProjectionTasksConvergeAndEqualUvUsesNumericPostId() throws Exception {
        long first = newPost(), second = first + 1;
        Clock now = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
        String namespace = namespace();
        var a = projection(namespace, now); var b = projection(namespace, now);
        daily(first, "2026-11-16", 1, "one"); daily(second, "2026-11-16", 9, "other");
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) tasks.add(i % 2 == 0 ? a::project : b::project);
            var results = workers.invokeAll(tasks);
            for (var result : results) assertNotNull(result.get());
            var entries = a.read().entries();
            int firstIndex = -1, secondIndex = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).postId() == first) firstIndex = i;
                if (entries.get(i).postId() == second) secondIndex = i;
            }
            assertTrue(firstIndex >= 0 && secondIndex > firstIndex);
            assertTrue(a.read().generation() >= 1);
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void failedControlledRedisConnectionLeavesDatabaseSafeAndNextProjectionRebuilds() throws Exception {
        long post = newPost();
        Clock now = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
        String namespace = namespace();
        daily(post, "2026-11-16", 3, "same", "other");
        int unavailablePort;
        try (var reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { unavailablePort = reservation.getLocalPort(); }
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ZERO).build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", unavailablePort), client);
        try {
            factory.afterPropertiesSet(); factory.start();
            var unavailableRedis = new StringRedisTemplate(factory);
            unavailableRedis.afterPropertiesSet();
            var failed = new ViewProjection(jdbc, unavailableRedis, new TransactionTemplate(transactions), new SimpleMeterRegistry(), namespace, now);
            assertThrows(RuntimeException.class, failed::project);
            assertEquals(3L, jdbc.queryForObject("SELECT pv FROM xl_view_daily WHERE post_id=? AND view_date='2026-11-16'", Long.class, post));
            var restored = projection(namespace, now);
            assertFalse(restored.read().available());
            assertTrue(restored.project());
            var result = restored.read().entries().stream().filter(entry -> entry.postId() == post).findFirst().orElseThrow();
            assertEquals(3, result.pv()); assertEquals(2, result.uv());
        } finally { factory.destroy(); }
    }

    @Test void realForumProjectionFiltersHiddenDeletedAndReviewRejectedThenRestoresAnonymousPost() {
        String marker = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('view-projection-it',?,'统计回归用户')", marker);
        long owner = jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='view-projection-it' AND wechat_open_id=?", Long.class, marker);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))", owner);
        long session = jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", Long.class, owner);
        var actor = new AuthPrincipal(owner, session, "user");
        List<Long> created = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                long post = Long.parseLong(forum.create(new ForumModels.CreatePost("浏览热榜回归" + marker + ":" + i, "campus", List.of(), true, UUID.randomUUID().toString()), actor).get("id").toString());
                created.add(post);
                // Deterministic isolated snapshot: the public query must still authorize every ID.
                daily(post, "2026-11-16", 20 - i, "visitor:" + i);
            }
            Clock now = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
            var projection = projection(namespace(), now);
            var captured = projection.capture();
            List<ViewProjection.Entry> scoped = new ArrayList<>();
            for (int i = 0; i < created.size(); i++) scoped.add(new ViewProjection.Entry(created.get(i), 20 - i, 1));
            // Publish only this test's exact committed counts into its isolated Redis namespace;
            // unrelated retained integration fixtures must not decide these visibility assertions.
            assertTrue(projection.publish(new ViewProjection.Snapshot(captured.generation(), captured.startDate(),
                    captured.endDate(), captured.updatedAt(), scoped, true)));
            var ranking = new ViewRankingService(projection, forum);
            List<String> before = postIds(ranking.read(actor));
            assertEquals(created.subList(0, 5).stream().map(Object::toString).toList(), before);
            long hidden = created.getFirst(), deleted = created.get(1), rejected = created.get(2);
            jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?", hidden);
            jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?", deleted);
            jdbc.update("UPDATE xl_post SET review_state='pending' WHERE id=?", rejected);
            var filtered = ranking.read(actor);
            var filteredIds = postIds(filtered);
            assertFalse(filteredIds.contains(Long.toString(hidden)));
            assertFalse(filteredIds.contains(Long.toString(deleted)));
            assertFalse(filteredIds.contains(Long.toString(rejected)));
            assertEquals(5, filteredIds.size(), "Filtering must continue through the complete absolute ranking");
            assertEquals(created.subList(3, 8).stream().map(Object::toString).toList(), filteredIds);
            assertAnonymous(filtered);
            jdbc.update("UPDATE xl_post SET status='visible' WHERE id=?", hidden);
            jdbc.update("UPDATE xl_post SET deleted_at=NULL WHERE id=?", deleted);
            jdbc.update("UPDATE xl_post SET review_state='normal' WHERE id=?", rejected);
            assertEquals(before, postIds(ranking.read(null)), "Restoration is immediate without a new event or Redis rebuild");
            assertAnonymous(ranking.read(null));
        } finally {
            for (long post : created) jdbc.update("UPDATE xl_post SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE id=?", post);
        }
    }

    @SuppressWarnings("unchecked") private List<String> postIds(Map<String,Object> response) {
        return ((List<Map<String,Object>>)response.get("items")).stream().map(item -> ((Map<?,?>)item.get("post")).get("id").toString()).toList();
    }
    @SuppressWarnings("unchecked") private void assertAnonymous(Map<String,Object> response) {
        for (var item : (List<Map<String,Object>>)response.get("items")) {
            var author = (Map<?,?>)((Map<?,?>)item.get("post")).get("author");
            assertEquals(true, author.get("isAnonymous")); assertNull(author.get("id"));
        }
        assertFalse(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(response).contains("visitor_key"));
    }

    private ViewProjection projection(String namespace, Clock clock) {
        return new ViewProjection(jdbc, redis, new TransactionTemplate(transactions), new SimpleMeterRegistry(), namespace, clock);
    }
    private String namespace() { return "projection_it_" + UUID.randomUUID().toString().replace("-", ""); }
    private long newPost() { return 8_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L) * 10; }
    private void daily(long post, String date, long pv, String... visitors) {
        jdbc.update("INSERT INTO xl_view_daily(post_id,view_date,pv) VALUES(?,?,?)", post, java.sql.Date.valueOf(date), pv);
        for (String visitor : visitors) {
            String key = HexFormat.of().formatHex(cn.xinkelink.content.ContentSupport.hash(visitor));
            jdbc.update("INSERT INTO xl_view_visitor_day(post_id,view_date,visitor_key) VALUES(?,?,?)", post, java.sql.Date.valueOf(date), key);
        }
    }
}
