package cn.xinkelink.forum.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static cn.xinkelink.forum.metrics.PostMetricsCache.Counts;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostMetricsL1Test {
    private static final Counts ORIGINAL = new Counts(7, 3);
    private static final Counts CHANGED = new Counts(8, 4);
    private static final String KEY1 = "xinkelink:cache:v3:post-metrics:1";
    private static final String KEY2 = "xinkelink:cache:v3:post-metrics:2";
    private static final String LEGACY1 = "xinkelink:cache:v1:post-metrics:1";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> stored = new HashMap<>();
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<PostMetricsCache> subscribers = new ArrayList<>();
    private PostMetricsCache cache;
    private MockPostMetricsRedis backend;

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyCollection())).thenAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            return keys.stream().map(stored::get).toList();
        });
        doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            callback.execute(redis);
            return List.of();
        });
        when(redis.delete(anyCollection())).thenAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            long existing = keys.stream().filter(stored::containsKey).count();
            keys.forEach(stored::remove);
            return existing;
        });
        when(redis.convertAndSend(anyString(), any())).thenAnswer(invocation -> {
            String payload = invocation.getArgument(1);
            subscribers.forEach(subscriber -> subscriber.receiveInvalidation(payload));
            return (long) subscribers.size();
        });
        backend = new MockPostMetricsRedis(redis, values, stored, clock);
        cache = create(true, true, 10000, 2000);
    }

    @Test void supportsIndependentL1Configuration() {
        assertDoesNotThrow(() -> PostMetricsCache.class.getConstructor(StringRedisTemplate.class,
                boolean.class, long.class, long.class, boolean.class, long.class, long.class, MeterRegistry.class));
    }

    @Test void l1HitDoesNotAccessRedisOrDatabase() {
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL)).get(1L));
        clearInvocations(redis, values);
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> fail("L1 hit cannot load SQL")).get(1L));
        verifyNoInteractions(redis, values);
        assertEquals(1, meters.counter("post.metrics.l1.hit").count());
        assertEquals(1, meters.counter("post.metrics.l1.miss").count());
    }

    @Test void l1MissL2HitUsesOneBatchReadAndNoDatabase() {
        seed(1, ORIGINAL, 20000);
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> fail("L2 hit cannot load SQL")).get(1L));
        verify(values).multiGet(List.of(KEY1));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        clearInvocations(redis, values);
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> fail("promoted L1 hit")).get(1L));
        verifyNoInteractions(redis, values);
    }

    @Test void bothMissesLoadOnlyUniqueMissingIdsInOneBatch() {
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        seed(2, CHANGED, 20000);
        clearInvocations(redis, values);
        var result = cache.get(List.of(1L, 2L, 2L, 3L, 3L, 4L), ids -> {
            assertEquals(List.of(3L, 4L), new ArrayList<>(ids));
            return Map.of(3L, ORIGINAL, 4L, CHANGED);
        });
        assertEquals(Map.of(1L, ORIGINAL, 2L, CHANGED, 3L, ORIGINAL, 4L, CHANGED), result);
        verify(values).multiGet(List.of(KEY2, "xinkelink:cache:v3:post-metrics:3", "xinkelink:cache:v3:post-metrics:4"));
        verify(values, times(2)).set(anyString(), anyString(), any(Duration.class));
        assertEquals(2, meters.timer("post.metrics.database.load").count());
    }

    @Test void readsDoNotRenewL1ExpireAfterWrite() {
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        clock.advanceMillis(1500);
        cache.get(List.of(1L), ids -> fail("still within L1 TTL"));
        clock.advanceMillis(501);
        clearInvocations(redis, values);
        cache.get(List.of(1L), ids -> fail("L2 valid after L1 expires"));
        verify(values).multiGet(List.of(KEY1));
    }

    @Test void l1CannotOutliveTheRemainingSourceValidity() {
        seed(1, ORIGINAL, 400);
        cache.get(List.of(1L), ids -> fail("initial L2 hit"));
        clock.advanceMillis(401);
        AtomicInteger loads = new AtomicInteger();
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> {
            loads.incrementAndGet(); return Map.of(1L, CHANGED);
        }).get(1L));
        assertEquals(1, loads.get(), "Expired source must not be reimported into L1");
    }

    @Test void repeatedL1ReimportsNeverExtendAnOldL2Deadline() {
        seed(1, ORIGINAL, 4500);
        for (int i = 0; i < 3; i++) {
            assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> fail("source still valid")).get(1L));
            clock.advanceMillis(2001);
        }
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void databaseLoadTimeConsumesTheSourceTtlBudget() {
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> {
            clock.advanceMillis(20001); return Map.of(1L, ORIGINAL);
        }).get(1L));
        assertTrue(stored.isEmpty(), "A slow old read cannot begin a fresh Redis TTL after completing");
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void caffeineCapacityIsBoundedAndEvictedIdsCanReload() {
        cache = create(true, true, 2, 2000);
        cache.get(List.of(1L, 2L, 3L, 4L, 5L), this::loadCounts);
        var l1 = localCache(cache);
        l1.cleanUp();
        assertTrue(l1.estimatedSize() <= 2);
        assertTrue(l1.stats().evictionCount() >= 3);
        stored.clear();
        var result = cache.get(List.of(1L, 2L, 3L, 4L, 5L), this::loadCounts);
        assertEquals(loadCounts(List.of(1L, 2L, 3L, 4L, 5L)), result);
    }

    @Test void threeSwitchModesReturnIdenticalPublicCounts() {
        for (var mode : List.of(List.of(false, false), List.of(true, false), List.of(true, true))) {
            stored.clear();
            var instance = create(mode.get(0), mode.get(1), 10000, 2000);
            AtomicInteger loads = new AtomicInteger();
            Function<Collection<Long>, Map<Long, Counts>> loader = ids -> {
                loads.incrementAndGet(); return loadCounts(ids);
            };
            assertEquals(loadCounts(List.of(1L, 2L)), instance.get(List.of(1L, 2L), loader));
            clearInvocations(redis, values);
            assertEquals(loadCounts(List.of(1L, 2L)), instance.get(List.of(1L, 2L), loader));
            if (!mode.get(0)) { assertEquals(2, loads.get()); verifyNoInteractions(redis, values); }
            else if (!mode.get(1)) { assertEquals(1, loads.get()); verify(values).multiGet(List.of(KEY1, KEY2)); }
            else { assertEquals(1, loads.get()); verifyNoInteractions(redis, values); }
        }
    }

    @Test void commitInvalidatesBothLevelsAndBroadcastsOnlyAfterSuccess() {
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        stored.put(LEGACY1, "7:3");
        clearInvocations(redis, values);
        transaction().executeWithoutResult(status -> {
            cache.invalidateAfterCommit(List.of(1L, 1L));
            assertTrue(stored.containsKey(KEY1));
            assertEquals(1, localCache(cache).estimatedSize());
            verify(redis, never()).convertAndSend(anyString(), any());
        });
        assertFalse(stored.containsKey(KEY1));
        assertFalse(stored.containsKey(LEGACY1));
        assertEquals(0, localCache(cache).estimatedSize());
        verify(redis).convertAndSend(anyString(), eq("1"));
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void rollbackDoesNotInvalidateEitherLevelOrBroadcast() {
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        clearInvocations(redis, values);
        transaction().executeWithoutResult(status -> {
            cache.invalidateAfterCommit(List.of(1L));
            status.setRollbackOnly();
        });
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> fail("rollback preserves L1")).get(1L));
        verifyNoInteractions(redis, values);
    }

    @Test void writeTransactionBypassesBothLevelsAndNeverCachesUncommittedCounts() {
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        String before = stored.get(KEY1);
        clearInvocations(redis, values);
        transaction().executeWithoutResult(status -> {
            assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
            assertEquals(before, stored.get(KEY1));
            status.setRollbackOnly();
        });
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> fail("uncommitted value never reaches L1")).get(1L));
        verifyNoInteractions(redis, values);
    }

    @Test void disabledCacheStillInvalidatesEnabledPeersAndLegacyKeys() {
        var peer = create(true, true, 10000, 2000);
        peer.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        subscribers.add(peer);
        stored.put(LEGACY1, "7:3");
        create(false, false, 10000, 2000).invalidateAfterCommit(List.of(1L));
        assertFalse(stored.containsKey(LEGACY1));
        assertEquals(CHANGED, peer.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void redisReadFailureFallsBackWithoutUnverifiedL1Promotion() {
        when(values.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL)).get(1L));
        assertEquals(0, localCache(cache).estimatedSize());
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test void redisFillFailurePreservesResponseWithoutUnverifiedL1Promotion() {
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL)).get(1L));
        assertEquals(0, localCache(cache).estimatedSize());
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
        assertTrue(stored.isEmpty());
    }

    @Test void batchFillFailureNeverLosesDatabaseCountsOrPublishesPrivateFields() {
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), any(Duration.class));
        var result = cache.get(List.of(1L, 2L), this::loadCounts);
        assertEquals(loadCounts(List.of(1L, 2L)), result);
        assertTrue(stored.isEmpty());
        assertEquals(0, localCache(cache).estimatedSize());
        clearInvocations(redis, values);
        AtomicInteger reloads = new AtomicInteger();
        assertEquals(result, cache.get(List.of(1L, 2L), ids -> { reloads.incrementAndGet(); return loadCounts(ids); }));
        assertEquals(1, reloads.get(), "An unconfirmed fill must not enter L1");
        verify(values).multiGet(List.of(KEY1, KEY2));
    }

    @Test void deletionFailureStillEvictsLocalAndBroadcastsToPeers() {
        var peer = create(true, true, 10000, 2000);
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        peer.get(List.of(1L), ids -> fail("peer loads Redis"));
        subscribers.add(peer);
        when(redis.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        assertDoesNotThrow(() -> cache.invalidateAfterCommit(List.of(1L)));
        assertEquals(0, localCache(cache).estimatedSize());
        assertEquals(0, localCache(peer).estimatedSize());
        verify(redis).convertAndSend(anyString(), eq("1"));
        // Failed L2 deletion is still bounded by its original source deadline.
        clock.advanceMillis(20001);
        assertEquals(CHANGED, peer.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void broadcastFailureDoesNotFailCommittedMutationAndPeerExpiresSoon() {
        var peer = create(true, true, 10000, 2000);
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        peer.get(List.of(1L), ids -> fail("peer loads Redis"));
        when(redis.convertAndSend(anyString(), any())).thenThrow(new RedisConnectionFailureException("down"));
        assertDoesNotThrow(() -> transaction().executeWithoutResult(status -> cache.invalidateAfterCommit(List.of(1L))));
        assertEquals(ORIGINAL, peer.get(List.of(1L), ids -> fail("missed message can briefly leave L1 stale")).get(1L));
        clock.advanceMillis(2001);
        assertEquals(CHANGED, peer.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void twoInstancesReceiveInvalidationAndRefreshThroughDatabase() {
        var peer = create(true, true, 10000, 2000);
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        peer.get(List.of(1L), ids -> fail("peer loads Redis"));
        subscribers.add(cache); subscribers.add(peer);
        cache.invalidateAfterCommit(List.of(1L));
        assertEquals(CHANGED, peer.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> fail("first peer filled Redis")).get(1L));
    }

    @Test void subscriptionRecoveryClearsValuesFromMissedInvalidations() {
        var peer = create(true, true, 10000, 2000);
        cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL));
        peer.get(List.of(1L), ids -> fail("peer loads Redis"));
        cache.invalidateAfterCommit(List.of(1L)); // peer disconnected, no Pub/Sub replay
        assertEquals(ORIGINAL, peer.get(List.of(1L), ids -> fail("offline peer remains briefly stale")).get(1L));
        peer.onSubscriptionRestored();
        assertEquals(CHANGED, peer.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void invalidationDuringDatabaseLoadSuppressesStaleRefill() {
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> {
            cache.invalidateAfterCommit(List.of(1L));
            return Map.of(1L, ORIGINAL);
        }).get(1L), "An already running reader may still return its old snapshot");
        assertTrue(stored.isEmpty(), "Observed invalidation prevents this old read from refilling L2");
        assertEquals(0, localCache(cache).estimatedSize());
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void invalidationDuringRedisReadSuppressesStaleL1Promotion() {
        seed(1, ORIGINAL, 20000);
        when(values.multiGet(anyCollection())).thenAnswer(invocation -> {
            String old = stored.get(KEY1);
            stored.clear();
            cache.receiveInvalidation("1");
            return Collections.singletonList(old);
        });
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> fail("in-flight Redis read")).get(1L));
        assertEquals(0, localCache(cache).estimatedSize());
        when(values.multiGet(anyCollection())).thenReturn(Collections.singletonList(null));
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
    }

    @Test void recoveryDuringLoadSuppressesOldRefill() {
        cache.get(List.of(1L), ids -> { cache.onSubscriptionRestored(); return Map.of(1L, ORIGINAL); });
        assertTrue(stored.isEmpty());
        assertEquals(0, localCache(cache).estimatedSize());
    }

    @Test void alreadyDispatchedFillIsRejectedAfterSharedInvalidation() {
        backend.beforeFill = () -> {
            backend.beforeFill = () -> {};
            cache.invalidateAfterCommit(List.of(1L));
        };
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL)).get(1L),
                "The in-flight reader may return its already read snapshot");
        assertEquals(0, localCache(cache).estimatedSize(), "Rejected old fill must not promote L1");
        assertFalse(stored.containsKey(KEY1), "The old expected generation must not recreate L2");
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> Map.of(1L, CHANGED)).get(1L));
        assertEquals(CHANGED, cache.get(List.of(1L), ids -> fail("fresh accepted fill enters L1")).get(1L));
    }

    @Test void invalidDatabaseCountsAndMissingRowsNeverPoisonEitherLevel() {
        for (Counts invalid : List.of(new Counts(-1, 3), new Counts(1, -3))) {
            cache.get(List.of(1L), ids -> Map.of(1L, invalid));
            assertTrue(stored.isEmpty());
            assertEquals(0, localCache(cache).estimatedSize());
        }
        assertTrue(cache.get(List.of(1L), ids -> Map.of()).isEmpty());
        assertTrue(stored.isEmpty());
        assertEquals(0, localCache(cache).estimatedSize());
    }

    @Test void databaseFailuresAreNeverConvertedToZeroOrHiddenByRedisOutage() {
        when(values.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        var failure = new IllegalStateException("database down");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> cache.get(List.of(1L), ids -> { throw failure; })));
        assertTrue(stored.isEmpty());
        assertEquals(0, localCache(cache).estimatedSize());
    }

    private PostMetricsCache create(boolean enabled, boolean l1Enabled, long maximumSize, long ttlMillis) {
        return new PostMetricsCache(redis, enabled, 20, 0, l1Enabled, maximumSize, ttlMillis,
                cache == null ? meters : new SimpleMeterRegistry(), clock, () -> clock.millis() * 1_000_000);
    }

    private void seed(long id, Counts value, long validMillis) {
        backend.seed("xinkelink:cache:v3:post-metrics:" + id,
                (clock.millis() + validMillis) + ":" + value.likeCount() + ":" + value.commentCount());
    }

    private Map<Long, Counts> loadCounts(Collection<Long> ids) {
        Map<Long, Counts> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new Counts(id, id + 1)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Cache<Long, ?> localCache(PostMetricsCache instance) {
        return (Cache<Long, ?>) Objects.requireNonNull(ReflectionTestUtils.getField(instance, "l1"));
    }

    private TransactionTemplate transaction() {
        var manager = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) {}
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        return new TransactionTemplate(manager);
    }

    private static final class MutableClock extends Clock {
        private long now = 1_900_000_000_000L;
        void advanceMillis(long millis) { now += millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
