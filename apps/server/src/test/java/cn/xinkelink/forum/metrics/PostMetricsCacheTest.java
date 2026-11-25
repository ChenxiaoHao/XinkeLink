package cn.xinkelink.forum.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static cn.xinkelink.forum.metrics.PostMetricsCache.Counts;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostMetricsCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> stored = new HashMap<>();
    private PostMetricsCache cache;
    private MockPostMetricsRedis backend;
    private static final String KEY1 = "xinkelink:cache:v3:post-metrics:1";
    private static final String KEY2 = "xinkelink:cache:v3:post-metrics:2";

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.executePipelined(any(org.springframework.data.redis.core.SessionCallback.class))).thenAnswer(invocation -> {
            org.springframework.data.redis.core.SessionCallback<?> callback = invocation.getArgument(0);
            callback.execute(redis); return List.of();
        });
        when(values.multiGet(anyCollection())).thenAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            return keys.stream().map(stored::get).toList();
        });
        doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyCollection())).thenAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            keys.forEach(stored::remove);
            return (long) keys.size();
        });
        backend = new MockPostMetricsRedis(redis, values, stored, java.time.Clock.systemUTC());
        cache = new PostMetricsCache(redis, true, 20, 0);
    }

    @Test
    void firstMissLoadsOnceAndNextHitDoesNotLoad() {
        AtomicInteger loads = new AtomicInteger();
        Function<Collection<Long>, Map<Long, Counts>> loader = ids -> {
            loads.incrementAndGet();
            return Map.of(1L, new Counts(7, 3));
        };
        assertEquals(new Counts(7, 3), cache.get(List.of(1L), loader).get(1L));
        assertEquals(new Counts(7, 3), cache.get(List.of(1L), loader).get(1L));
        assertEquals(1, loads.get());
        assertTrue(stored.get(KEY1).endsWith(":7:3"));
        verify(values).set(eq(KEY1), endsWith(":7:3"), argThat((Duration ttl) -> ttl.toMillis() > 19000 && ttl.toMillis() <= 20000));
    }

    @Test
    void batchLoadsOnlyMissingUniqueIdsAndPreservesHits() {
        backend.seed(KEY1, value("4:2"));
        AtomicInteger loads = new AtomicInteger();
        Map<Long, Counts> result = cache.get(List.of(1L, 2L, 2L, 3L), ids -> {
            assertEquals(List.of(2L, 3L), new ArrayList<>(ids));
            loads.incrementAndGet();
            return Map.of(2L, new Counts(6, 1), 3L, new Counts(0, 8));
        });
        assertEquals(Map.of(1L, new Counts(4, 2), 2L, new Counts(6, 1), 3L, new Counts(0, 8)), result);
        assertEquals(1, loads.get());
        verify(values).multiGet(List.of(KEY1, KEY2, "xinkelink:cache:v3:post-metrics:3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "-1:2", "1:-2", "1:2:3", "1:2:userId", "1:2 ", "+1:2", "1.0:2", "9223372036854775808:0", "0:9223372036854775808", "null:0"})
    void malformedValueIsARealDatabaseMiss(String malformed) {
        backend.seed(KEY1, malformed);
        AtomicInteger loads = new AtomicInteger();
        assertEquals(new Counts(9, 2), cache.get(List.of(1L), ids -> {
            loads.incrementAndGet();
            return Map.of(1L, new Counts(9, 2));
        }).get(1L));
        assertEquals(1, loads.get());
        assertTrue(stored.get(KEY1).endsWith(":9:2"));
    }

    @Test
    void acceptsNonnegativeLongBoundaries() {
        backend.seed(KEY1, value("9223372036854775807:0"));
        assertEquals(new Counts(Long.MAX_VALUE, 0), cache.get(List.of(1L), ids -> fail("cache hit")).get(1L));
    }

    @Test
    void redisReadFailureFallsBackToLoader() {
        when(values.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        assertEquals(new Counts(2, 5), cache.get(List.of(1L), ids -> Map.of(1L, new Counts(2, 5))).get(1L));
    }

    @Test
    void redisFillFailureDoesNotLoseLoadedCounts() {
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertEquals(new Counts(2, 5), cache.get(List.of(1L), ids -> Map.of(1L, new Counts(2, 5))).get(1L));
    }

    @Test
    void missingDatabaseRowsAreNotInventedOrCachedAsZero() {
        assertTrue(cache.get(List.of(1L), ids -> Map.of()).isEmpty());
        assertTrue(stored.isEmpty());
    }

    @Test
    void databaseFailurePropagatesUnchangedEvenWhenRedisFails() {
        when(values.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        var failure = new IllegalStateException("database down");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> cache.get(List.of(1L), ids -> { throw failure; })));
        assertTrue(stored.isEmpty());
    }

    @Test
    void disabledCacheAlwaysLoadsWithoutTouchingRedis() {
        cache = new PostMetricsCache(redis, false, 20, 0);
        assertEquals(new Counts(2, 5), cache.get(List.of(1L), ids -> Map.of(1L, new Counts(2, 5))).get(1L));
        verifyNoInteractions(redis);
    }

    @Test
    void disabledCacheStillInvalidatesOldKeysBeforeReenable() {
        cache = new PostMetricsCache(redis, false, 20, 0);
        backend.seed(KEY1, value("2:4"));
        cache.invalidateAfterCommit(List.of(1L));
        assertFalse(stored.containsKey(KEY1));
    }

    @Test
    void emptyRequestDoesNotTouchEitherStore() {
        assertTrue(cache.get(List.of(), ids -> fail("empty request")).isEmpty());
        cache.invalidateAfterCommit(List.of());
        verifyNoInteractions(redis);
    }

    @Test
    void noTransactionInvalidatesImmediatelyAndDeletionFailuresAreHarmless() {
        backend.seed(KEY1, value("2:4"));
        cache.invalidateAfterCommit(List.of(1L));
        assertFalse(stored.containsKey(KEY1));
        when(redis.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        assertDoesNotThrow(() -> cache.invalidateAfterCommit(List.of(1L)));
    }

    @Test
    void commitDeletesOnlyAfterCommit() {
        backend.seed(KEY1, value("2:4"));
        transaction(false).executeWithoutResult(status -> {
            cache.invalidateAfterCommit(List.of(1L));
            assertTrue(stored.get(KEY1).endsWith(":2:4"));
        });
        assertFalse(stored.containsKey(KEY1));
    }

    @Test
    void rollbackKeepsExistingCache() {
        backend.seed(KEY1, value("2:4"));
        transaction(false).executeWithoutResult(status -> {
            cache.invalidateAfterCommit(List.of(1L));
            status.setRollbackOnly();
        });
        assertEquals(new Counts(2, 4), cache.get(List.of(1L), ids -> fail("preserved hit")).get(1L));
        verify(redis, never()).delete(anyCollection());
    }

    @Test
    void writeTransactionBypassesCachedValueAndNeverFillsUncommittedCounts() {
        backend.seed(KEY1, value("2:4"));
        transaction(false).executeWithoutResult(status -> {
            assertEquals(new Counts(8, 9), cache.get(List.of(1L, 2L), ids -> Map.of(1L, new Counts(8, 9))).get(1L));
            status.setRollbackOnly();
        });
        assertEquals(1, stored.size()); assertTrue(stored.get(KEY1).endsWith(":2:4"));
        verifyNoInteractions(redis);
    }

    @Test
    void readOnlyTransactionUsesCache() {
        backend.seed(KEY1, value("2:4"));
        transaction(true).executeWithoutResult(status -> assertEquals(new Counts(2, 4),
                cache.get(List.of(1L), ids -> fail("read only hit")).get(1L)));
    }

    @Test
    void eachFilledKeyGetsItsOwnBoundedTtl() {
        cache = new PostMetricsCache(redis, true, 20, 10);
        cache.get(List.of(1L, 2L), ids -> Map.of(1L, new Counts(2, 4), 2L, new Counts(3, 5)));
        var ttl = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(values, times(2)).set(anyString(), anyString(), ttl.capture());
        assertTrue(ttl.getAllValues().stream().allMatch(value -> value.toMillis() >= 19900 && value.toMillis() <= 30000));
    }

    private static String value(String counts) { return (System.currentTimeMillis() + 19000) + ":" + counts; }

    private TransactionTemplate transaction(boolean readOnly) {
        var manager = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) {}
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        var template = new TransactionTemplate(manager);
        template.setReadOnly(readOnly);
        return template;
    }
}
