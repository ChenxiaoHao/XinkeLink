package cn.xinkelink.forum;

import cn.xinkelink.forum.metrics.PostMetricsCache;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real database snapshot tests; only the existing dedicated cache IT database is allowed. */
@SpringBootTest
@ActiveProfiles({"db", "auth"})
class PostMetricsSnapshotIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ForumService forum;
    @Autowired ForumMapper mapper;
    @Autowired PostMetricsCache cache;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    long post, author, actor;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostMetricsSnapshotIT::isolatedUrl);
        registry.add("spring.flyway.url", PostMetricsSnapshotIT::isolatedUrl);
        registry.add("xinkelink.auth.jwt.secret-base64", () -> System.getenv("XINKELINK_JWT_SECRET_BASE64"));
        registry.add("xinkelink.cache.post-metrics.enabled", () -> "true");
        registry.add("xinkelink.cache.post-metrics.l1.enabled", () -> "true");
    }
    static String isolatedUrl() {
        String url=System.getenv("XINKELINK_DB_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_[a-zA-Z0-9_]+_test(?:\\?.*)?"))
            throw new IllegalStateException("Snapshot IT requires the dedicated cache database on 13306");
        return url;
    }
    @BeforeEach void fixture() throws Exception {
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:13306/xinkelink_cache_"));
            assertEquals(Connection.TRANSACTION_READ_COMMITTED,connection.getTransactionIsolation(),"Actual pool isolation must be inspected");
        }
        author=user(); actor=user();
        String request=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_post(author_id,category,content,is_anonymous,anonymous_label,client_request_id,request_hash) VALUES(?,'campus','snapshot fixture',1,'匿名同学',?,UNHEX(SHA2(?,256)))",author,request,request);
        post=jdbc.queryForObject("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?",Long.class,author,request);
        cache.invalidateAfterCommit(List.of(post));
    }
    @AfterEach void cleanup() {
        if(post!=0) { jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",post); cache.invalidateAfterCommit(List.of(post)); }
        for(long id:List.of(author,actor)) if(id!=0) jdbc.update("UPDATE xl_user SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",id);
    }
    private long user() {
        String open=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('metrics-snapshot',?,'快照回归')",open);
        return jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='metrics-snapshot' AND wechat_open_id=?",Long.class,open);
    }
    private long sqlLikes() { return jdbc.queryForObject("SELECT COUNT(*) FROM xl_like WHERE target_type='post' AND target_id=?",Long.class,post); }
    private void addLike() { jdbc.update("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)",actor,post); }
    private TransactionTemplate transaction(boolean readOnly,int isolation) {
        var tx=new TransactionTemplate(transactions); tx.setReadOnly(readOnly); tx.setIsolationLevel(isolation); return tx;
    }
    private Map<Long,PostMetricsCache.Counts> counts(Collection<Long> ids) {
        return mapper.publicPostMetrics(ids).stream().collect(Collectors.toMap(ForumModels.PublicPostMetric::id,
                row -> new PostMetricsCache.Counts(row.likeCount(),row.commentCount())));
    }
    private long cachedLikes() { return ((Number)forum.detail(post,null).get("likeCount")).longValue(); }

    @Test void oldRepeatableReadSnapshotCannotPolluteCacheAfterCommittedInvalidation() throws Exception {
        var established=new CountDownLatch(1);
        var committed=new CountDownLatch(1);
        try(var workers=Executors.newSingleThreadExecutor()) {
            Future<Long> oldReader=workers.submit(() -> transaction(true,TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status -> {
                assertEquals(0,sqlLikes(),"First statement establishes an old consistent snapshot");
                established.countDown();
                try { assertTrue(committed.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new RuntimeException(e); }
                assertEquals(0,sqlLikes(),"The outer RR snapshot must remain old after another transaction commits");
                long view=cachedLikes();
                assertEquals(0,view,"RR reads may keep their own old snapshot but must bypass both caches");
                assertEquals(0,sqlLikes(),"Cache policy must not alter or remove the outer snapshot");
                return view;
            }));
            try {
                assertTrue(established.await(10,TimeUnit.SECONDS));
                transaction(false,TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> { addLike(); cache.invalidateAfterCommit(List.of(post)); });
            } finally { committed.countDown(); }
            oldReader.get(15,TimeUnit.SECONDS);
        }
        assertFalse(Boolean.TRUE.equals(redis.hasKey("xinkelink:cache:v3:post-metrics:"+post)),
                "The old RR reader must not publish its snapshot under the new shared token");
        assertEquals(1,sqlLikes());
        assertEquals(1,cachedLikes(),"Old transaction snapshot must never become a newly versioned shared or L1 cache value");
    }

    @Test void writableTransactionReadsItsOwnUncommittedCountsAndRollbackDoesNotPublish() {
        assertEquals(0,cachedLikes());
        transaction(false,TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            addLike(); cache.invalidateAfterCommit(List.of(post));
            assertEquals(1,cache.get(List.of(post),this::counts).get(post).likeCount(),"Writing transaction must use its current connection");
            status.setRollbackOnly();
        });
        assertEquals(0,sqlLikes());
        assertEquals(0,cachedLikes(),"Rollback must leave the committed warm count unchanged");
    }

    @Test void repeatableReadBypassesNewlyWarmL1AndPreservesItsOwnSnapshot() throws Exception {
        var established=new CountDownLatch(1);
        var warmCommittedCache=new CountDownLatch(1);
        try(var workers=Executors.newSingleThreadExecutor()) {
            var reader=workers.submit(() -> transaction(true,TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status -> {
                assertEquals(0,sqlLikes()); established.countDown();
                try { assertTrue(warmCommittedCache.await(10,TimeUnit.SECONDS)); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                assertEquals(0,cachedLikes(),"A repeatable read must use its own old snapshot even when L1 holds newer counts");
                return sqlLikes();
            }));
            try {
                assertTrue(established.await(10,TimeUnit.SECONDS));
                transaction(false,TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                    addLike(); cache.invalidateAfterCommit(List.of(post));
                });
                assertEquals(1,cachedLikes(),"Independent READ COMMITTED request warms the newly committed counts");
            } finally { warmCommittedCache.countDown(); }
            assertEquals(0,reader.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,cachedLikes(),"The old reader must not overwrite the newer shared or local value");
    }

    @Test void commitInvalidatesWarmCountsAndFreshReadLoadsCommittedState() {
        assertEquals(0,cachedLikes());
        transaction(false,TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            addLike(); cache.invalidateAfterCommit(List.of(post));
            assertEquals(1,cache.get(List.of(post),this::counts).get(post).likeCount());
        });
        assertEquals(1,cachedLikes());
    }
}
