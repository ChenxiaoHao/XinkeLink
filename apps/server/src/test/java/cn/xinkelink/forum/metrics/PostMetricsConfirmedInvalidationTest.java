package cn.xinkelink.forum.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostMetricsConfirmedInvalidationTest {
    private static final String DATA = PostMetricsFence.PREFIX + "1";
    private static final String GENERATION = PostMetricsFence.PREFIX + "generation:1";
    private static final PostMetricsCache.Counts ORIGINAL = new PostMetricsCache.Counts(2, 3);
    private static final PostMetricsCache.Counts FRESH = new PostMetricsCache.Counts(3, 4);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> stored = new HashMap<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private PostMetricsCache cache;
    private MockPostMetricsRedis backend;

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyCollection())).thenAnswer(call -> {
            Collection<String> keys = call.getArgument(0);
            return keys.stream().map(stored::get).toList();
        });
        doAnswer(call -> {
            stored.put(call.getArgument(0), call.getArgument(1)); return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyCollection())).thenAnswer(call -> {
            Collection<String> keys = call.getArgument(0); keys.forEach(stored::remove); return (long)keys.size();
        });
        backend = new MockPostMetricsRedis(redis, values, stored, Clock.systemUTC());
        cache = new PostMetricsCache(redis, true, 20, 0, true, 10000, 2000, meters);
    }

    @Test void confirmedConsumerEntryMustSurfaceAnUnconfirmedSharedFailure() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(PostMetricsFence.INVALIDATE), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("private transport detail"));
        var cache = new PostMetricsCache(redis, true, 20, 0, true, 10000, 2000, new SimpleMeterRegistry());
        var method = assertDoesNotThrow(() -> PostMetricsCache.class.getMethod("invalidateConfirmed", Collection.class),
                "CDC needs an explicit synchronous confirmation contract; legacy best-effort void is insufficient");
        var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(cache, List.of(1L)));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertFalse(failure.getCause().getMessage().contains("private transport detail"));
    }

    @Test void sharedSuccessSynchronouslyDeletesBothLayersAndAdvancesTheVersion() {
        warm(); String old = backend.versions.get(GENERATION);
        cache.invalidateConfirmed(List.of(1L, 1L));
        assertEquals(0, localSize());
        assertFalse(stored.containsKey(DATA));
        assertNotEquals(old, backend.versions.get(GENERATION));
        verify(redis).execute(eq(PostMetricsFence.INVALIDATE), eq(List.of(GENERATION, DATA,
                "xinkelink:cache:v2:post-metrics:1", "xinkelink:cache:v1:post-metrics:1")), any(Object[].class));
        verify(redis).convertAndSend(eq(PostMetricsCache.CHANNEL), eq("1"));
        assertEquals(FRESH, cache.get(List.of(1L), ids -> Map.of(1L, FRESH)).get(1L));
    }

    @Test void sharedFailureThrowsSanitizedExceptionButStillClearsL1AndAttemptsBroadcast() {
        warm();
        when(redis.execute(eq(PostMetricsFence.INVALIDATE), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("sensitive endpoint and credentials"));
        var failure = assertThrows(PostMetricsCache.InvalidationNotConfirmedException.class,
                () -> cache.invalidateConfirmed(List.of(1L)));
        assertEquals("Shared post metrics invalidation was not confirmed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, localSize());
        verify(redis).convertAndSend(eq(PostMetricsCache.CHANNEL), eq("1"));
        assertEquals(1, meters.counter("post.metrics.redis.errors", "operation", "delete").count());
    }

    @Test void missingOrUnexpectedResultCannotBeAcknowledgedAsSharedSuccess() {
        for (Long result : Arrays.asList(null, 0L, 2L)) {
            when(redis.execute(eq(PostMetricsFence.INVALIDATE), anyList(), any(Object[].class))).thenReturn(result);
            assertThrows(PostMetricsCache.InvalidationNotConfirmedException.class,
                    () -> cache.invalidateConfirmed(List.of(1L)));
        }
        verify(redis, times(3)).convertAndSend(eq(PostMetricsCache.CHANNEL), eq("1"));
    }

    @Test void executedButLostResultStillThrowsAndRetrySafelyAdvancesAgain() {
        warm(); String original = backend.versions.get(GENERATION);
        String executed = "b".repeat(40) + "." + "c".repeat(40);
        when(redis.execute(eq(PostMetricsFence.INVALIDATE), anyList(), any(Object[].class))).thenAnswer(call -> {
            backend.versions.put(GENERATION, executed); stored.remove(DATA); return null;
        });
        assertThrows(PostMetricsCache.InvalidationNotConfirmedException.class,
                () -> cache.invalidateConfirmed(List.of(1L)));
        assertEquals(0, localSize()); assertFalse(stored.containsKey(DATA));
        assertNotEquals(original, backend.versions.get(GENERATION));
        // Model the next server-confirmed opaque successor. Real generation uniqueness is
        // separately tested with Lua; this case verifies the caller's unknown-result policy.
        String successor = "c".repeat(40) + "." + "d".repeat(40);
        when(redis.execute(eq(PostMetricsFence.INVALIDATE), anyList(), any(Object[].class))).thenAnswer(call -> {
            backend.versions.put(GENERATION, successor); stored.remove(DATA); return 1L;
        });
        assertDoesNotThrow(() -> cache.invalidateConfirmed(List.of(1L)));
        assertNotEquals(executed, backend.versions.get(GENERATION));
        assertNotEquals(original, backend.versions.get(GENERATION));
        assertEquals(FRESH, cache.get(List.of(1L), ids -> Map.of(1L, FRESH)).get(1L));
    }

    @Test void broadcastFailureDoesNotUndoConfirmedSharedSuccess() {
        warm();
        when(redis.convertAndSend(anyString(), any())).thenThrow(new RedisConnectionFailureException("publish unavailable"));
        assertDoesNotThrow(() -> cache.invalidateConfirmed(List.of(1L)));
        assertFalse(stored.containsKey(DATA)); assertEquals(0, localSize());
        assertEquals(1, meters.counter("post.metrics.redis.errors", "operation", "publish").count());
    }

    @Test void bothTransportFailuresKeepSharedFailureVisible() {
        when(redis.execute(eq(PostMetricsFence.INVALIDATE), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("shared unavailable"));
        when(redis.convertAndSend(anyString(), any())).thenThrow(new RedisConnectionFailureException("publish unavailable"));
        assertThrows(PostMetricsCache.InvalidationNotConfirmedException.class,
                () -> cache.invalidateConfirmed(List.of(1L)));
        assertEquals(1, meters.counter("post.metrics.redis.errors", "operation", "delete").count());
        assertEquals(1, meters.counter("post.metrics.redis.errors", "operation", "publish").count());
    }

    @Test void businessAfterCommitRemainsBestEffortOnSharedAndBroadcastFailure() {
        when(redis.execute(eq(PostMetricsFence.INVALIDATE), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("shared unavailable"));
        when(redis.convertAndSend(anyString(), any())).thenThrow(new RedisConnectionFailureException("publish unavailable"));
        assertDoesNotThrow(() -> transaction(false).executeWithoutResult(status -> cache.invalidateAfterCommit(List.of(1L))));
        assertDoesNotThrow(() -> cache.invalidateAfterCommit(List.of(1L)));
        verify(redis, times(2)).convertAndSend(anyString(), eq("1"));
    }

    @Test void activeReadOrWriteTransactionCannotRegisterDeferredConfirmedSuccess() {
        for (boolean readOnly : List.of(false, true)) {
            transaction(readOnly).executeWithoutResult(status -> {
                assertThrows(IllegalStateException.class, () -> cache.invalidateConfirmed(List.of(1L)));
                assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty(),
                        "The strict entry must never substitute an afterCommit registration for confirmation");
            });
        }
        verifyNoInteractions(redis, values);
    }

    @Test void emptyInputIsAConfirmedNoOp() {
        cache.invalidateConfirmed(List.of());
        verifyNoInteractions(redis, values);
    }

    @Test void cacheDisabledStillPerformsConfirmedSharedInvalidation() {
        warm();
        var disabled = new PostMetricsCache(redis, false, 20, 0, false, 10000, 2000, new SimpleMeterRegistry());
        disabled.invalidateConfirmed(List.of(1L));
        assertFalse(stored.containsKey(DATA));
        verify(redis).convertAndSend(anyString(), eq("1"));
    }

    @Test void confirmedEntryWaitsForTheActualSharedResultRatherThanSubmittingATask() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(redis.execute(eq(PostMetricsFence.INVALIDATE), anyList(), any(Object[].class))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "Shared operation was never released");
            return 1L;
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?> call = pool.submit(() -> cache.invalidateConfirmed(List.of(1L)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(call.isDone(), "The caller must wait for a shared confirmation");
                verify(redis, never()).convertAndSend(anyString(), any());
            } finally { release.countDown(); }
            call.get(5, TimeUnit.SECONDS);
        }
        verify(redis).convertAndSend(anyString(), eq("1"));
    }

    private void warm() {
        assertEquals(ORIGINAL, cache.get(List.of(1L), ids -> Map.of(1L, ORIGINAL)).get(1L));
        assertEquals(1, localSize());
        clearInvocations(redis, values);
    }
    private long localSize() {
        return ((Cache<?, ?>)Objects.requireNonNull(ReflectionTestUtils.getField(cache, "l1"))).estimatedSize();
    }
    private TransactionTemplate transaction(boolean readOnly) {
        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        };
        var template = new TransactionTemplate(manager); template.setReadOnly(readOnly); return template;
    }
}
