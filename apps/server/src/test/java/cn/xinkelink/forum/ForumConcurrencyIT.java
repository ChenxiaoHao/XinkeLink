package cn.xinkelink.forum;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"db", "auth"})
@Import(ForumConcurrencyIT.QueryConfiguration.class)
class ForumConcurrencyIT {
    private static final String SECRET = randomSecret();
    @Autowired ForumService forum;
    @Autowired AuthService auth;
    @Autowired JwtTokenService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired QueryCounter queries;
    private final List<WechatIdentity> identities = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> SECRET);
    }

    @Test
    void concurrentCreateRetriesCommitOnePostAndOneImageReference() throws Exception {
        AuthPrincipal author = user();
        String request = UUID.randomUUID().toString();
        var input = new ForumModels.CreatePost("并发创建", "study", List.of(image(author)), false, request);

        List<Map<String, Object>> results = concurrently(4, () -> forum.create(input, author));

        assertEquals(1, results.stream().map(value -> value.get("id")).distinct().count());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM xl_post WHERE author_id=? AND client_request_id=?
                """, Integer.class, author.subjectId(), request));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM xl_media_ref WHERE owner_type='post' AND owner_id=?
                """, Integer.class, results.getFirst().get("id")));
    }

    @Test
    void concurrentLikesAndFavoritesChangeEachRelationshipOnce() throws Exception {
        AuthPrincipal author = user();
        AuthPrincipal reader = user();
        long post = post(author, "并发互动", List.of());

        List<Map<String, Object>> likes = concurrently(4, () -> forum.likePost(post, true, reader));
        List<Map<String, Object>> favorites = concurrently(4, () -> forum.favorite(post, true, reader));

        assertEquals(1, likes.stream().filter(value -> Boolean.TRUE.equals(value.get("changed"))).count());
        assertEquals(1, favorites.stream().filter(value -> Boolean.TRUE.equals(value.get("changed"))).count());
        assertTrue(likes.stream().allMatch(value -> Long.valueOf(1).equals(value.get("likeCount"))));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM xl_like WHERE user_id=? AND target_type='post' AND target_id=?
                """, Integer.class, reader.subjectId(), post));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM xl_favorite WHERE user_id=? AND target_type='post' AND target_id=?
                """, Integer.class, reader.subjectId(), post));
        var detail = forum.detail(post, reader);
        assertEquals(1L, detail.get("likeCount"));
        assertEquals(true, detail.get("liked"));
        assertEquals(true, detail.get("favorited"));
    }

    @Test
    void deletingRootWhileAnotherUserRepliesLeavesNoPublicOrphan() throws Exception {
        AuthPrincipal author = user();
        AuthPrincipal reader = user();
        long post = post(author, "删除与回复竞争", List.of());
        // 多次独立起跑，两个操作均经真实服务事务提交，清理在工作线程退出之后。
        for (int attempt = 0; attempt < 6; attempt++) {
            var created = forum.createComment(post,
                    new ForumModels.CreateComment("一级评论", null, UUID.randomUUID().toString()), author);
            long root = Long.parseLong(((Map<?, ?>) created.get("comment")).get("id").toString());
            List<String> results = concurrently(List.of(
                    () -> {
                        assertEquals(true, forum.deleteComment(root, 0, author).get("deleted"));
                        return "DELETED";
                    },
                    () -> {
                        try {
                            var reply = forum.createComment(post, new ForumModels.CreateComment(
                                    "竞争中的回复", Long.toString(root), UUID.randomUUID().toString()), reader);
                            assertEquals(Long.toString(root), ((Map<?, ?>) reply.get("comment")).get("rootId"));
                            return "REPLIED";
                        } catch (BusinessException failure) {
                            assertEquals(ErrorCode.REPLY_TARGET_INVALID, failure.code());
                            return failure.code().name();
                        }
                    }));

            assertEquals("DELETED", results.getFirst());
            assertTrue(List.of("REPLIED", "REPLY_TARGET_INVALID").contains(results.getLast()));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM xl_comment c LEFT JOIN xl_comment r ON r.id=c.root_id
                    WHERE c.target_type='post' AND c.target_id=? AND c.root_id=?
                    AND c.deleted_at IS NULL AND c.status='visible'
                    AND (r.id IS NULL OR r.deleted_at IS NOT NULL OR r.status<>'visible')
                    """, Integer.class, post, root));
            assertTrue(forum.comments(post, null, 20, reader).items().isEmpty());
            assertEquals(0L, forum.detail(post, reader).get("commentCount"));
            BusinessException unavailable = assertThrows(BusinessException.class,
                    () -> forum.replies(root, null, 20, reader));
            assertEquals(ErrorCode.TARGET_UNAVAILABLE, unavailable.code());
        }
    }

    @Test
    void listingTwentyPostsUsesTheSameBoundedQueriesAsOnePost() {
        AuthPrincipal author = user();
        String keyword = "it-query-" + UUID.randomUUID().toString().replace("-", "");
        for (int i = 0; i < 20; i++) {
            AuthPrincipal owner = i == 0 ? author : user();
            post(owner, keyword + " " + i, List.of(image(owner)));
        }

        // 计数只覆盖本线程的列表调用；图片、作者和指标必须随页批量读取。
        int one = queries.count(() -> {
            var page = forum.list("study", "latest", keyword, null, 1, author);
            assertEquals(1, page.items().size());
            assertEquals(1, ((List<?>) page.items().getFirst().get("images")).size());
        });
        int twenty = queries.count(() -> {
            var page = forum.list("study", "latest", keyword, null, 20, author);
            assertEquals(20, page.items().size());
            assertTrue(page.items().stream().allMatch(value -> ((List<?>) value.get("images")).size() == 1));
            assertEquals(20, page.items().stream().map(value -> ((Map<?, ?>) value.get("author")).get("id")).distinct().count());
        });

        assertTrue(one >= 4, "必须拦截到真实查询，禁止零查询假通过");
        assertTrue(twenty <= 10, "列表查询数必须保持有界，实际为 " + twenty);
        assertEquals(one, twenty, "页大小增加不能产生逐条加载查询");
    }

    private AuthPrincipal user() {
        WechatIdentity identity = new WechatIdentity("ifc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 28),
                "forum-concurrency-" + UUID.randomUUID().toString().replace("-", ""));
        identities.add(identity);
        var login = auth.loginUser(identity);
        var token = jwt.decode(login.accessToken());
        return new AuthPrincipal(Long.parseLong(token.getSubject()),
                Long.parseLong(token.getClaimAsString("sid")), token.getClaimAsString("actorType"));
    }

    private long post(AuthPrincipal author, String content, List<String> images) {
        return Long.parseLong(forum.create(new ForumModels.CreatePost(content, "study", images,
                false, UUID.randomUUID().toString()), author).get("id").toString());
    }

    private String image(AuthPrincipal author) {
        String key = "forum-concurrency-it/" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO xl_image_asset(owner_user_id,purpose,storage_key,media_type,byte_size,width,height,sha256,expires_at)
                VALUES (?,'post',?,'image/png',100,100,100,UNHEX(SHA2('fixture',256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))
                """, author.subjectId(), key);
        return Long.toString(jdbc.queryForObject("SELECT id FROM xl_image_asset WHERE storage_key=?", Long.class, key));
    }

    @AfterEach
    void cleanup() {
        // 只取本测试完整随机身份；先按子评论ID倒序删除，避免自引用外键阻止清理。
        List<Long> users = new ArrayList<>();
        for (WechatIdentity identity : identities) users.addAll(jdbc.queryForList("""
                SELECT id FROM xl_user WHERE wechat_app_id=? AND wechat_open_id=?
                """, Long.class, identity.appId(), identity.openId()));
        List<Long> comments = new ArrayList<>();
        for (long user : users) comments.addAll(jdbc.queryForList("SELECT id FROM xl_comment WHERE author_id=?", Long.class, user));
        comments.sort(Comparator.reverseOrder());
        for (long comment : comments) {
            jdbc.update("DELETE FROM xl_like WHERE target_type='comment' AND target_id=?", comment);
            jdbc.update("DELETE FROM xl_comment WHERE id=?", comment);
        }
        for (long user : users) {
            jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id=? OR actor_id=?", user, user);
            jdbc.update("DELETE FROM xl_like WHERE user_id=?", user);
            jdbc.update("DELETE FROM xl_favorite WHERE user_id=?", user);
            for (long post : jdbc.queryForList("SELECT id FROM xl_post WHERE author_id=?", Long.class, user)) {
                jdbc.update("DELETE FROM xl_media_ref WHERE owner_type='post' AND owner_id=?", post);
                jdbc.update("DELETE FROM xl_like WHERE target_type='post' AND target_id=?", post);
                jdbc.update("DELETE FROM xl_favorite WHERE target_type='post' AND target_id=?", post);
            }
            jdbc.update("DELETE FROM xl_post WHERE author_id=?", user);
            jdbc.update("DELETE FROM xl_image_asset WHERE owner_user_id=?", user);
            jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", user);
            jdbc.update("DELETE FROM xl_user WHERE id=?", user);
        }
    }

    private static <T> List<T> concurrently(int workers, Callable<T> action) throws Exception {
        List<Callable<T>> actions = new ArrayList<>();
        for (int i = 0; i < workers; i++) actions.add(action);
        return concurrently(actions);
    }

    private static <T> List<T> concurrently(List<Callable<T>> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(actions.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> action : actions) futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS), "并发起跑等待超时");
                return action.call();
            }));
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

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QueryConfiguration {
        @Bean QueryCounter forumQueryCounter() { return new QueryCounter(); }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class,
                    RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class,
                    RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class})
    })
    static class QueryCounter implements Interceptor {
        private final ThreadLocal<Integer> calls = new ThreadLocal<>();

        int count(Runnable action) {
            calls.set(0);
            try {
                action.run();
                return calls.get();
            } finally {
                calls.remove();
            }
        }

        @Override public Object intercept(Invocation invocation) throws Throwable {
            Integer current = calls.get();
            if (current != null) calls.set(current + 1);
            return invocation.proceed();
        }
    }
}
