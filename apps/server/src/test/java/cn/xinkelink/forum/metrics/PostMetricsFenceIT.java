package cn.xinkelink.forum.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import com.github.benmanes.caffeine.cache.Cache;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Redis atomicity tests; only uniquely owned public-count test keys are touched. */
class PostMetricsFenceIT {
    private final long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    private final List<Long> ids = List.of(id);
    private final Set<Long> ownedIds = new LinkedHashSet<>(ids);
    private final PostMetricsCache.Counts old = new PostMetricsCache.Counts(1, 2);
    private final PostMetricsCache.Counts fresh = new PostMetricsCache.Counts(2, 2);
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach void connect() {
        factory = new LettuceConnectionFactory("127.0.0.1", 16379);
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
        assertEquals("PONG", redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping()));
    }

    @AfterEach void cleanup() {
        if (redis != null) redis.delete(ownedIds.stream().flatMap(owned -> List.of("xinkelink:cache:v1:post-metrics:" + owned,
                "xinkelink:cache:v2:post-metrics:" + owned, "xinkelink:cache:v3:post-metrics:" + owned,
                "xinkelink:cache:v3:post-metrics:generation:" + owned).stream()).toList());
        if (factory != null) factory.destroy();
    }

    @Test void oldFillArrivingAfterPeerInvalidationCannotPolluteSharedCache() throws Exception {
        var intercepted = new ControlledRedis(factory);
        var atWrite = new CountDownLatch(1);
        var resumeWrite = new CountDownLatch(1);
        intercepted.beforeFill = () -> {
            atWrite.countDown();
            await(resumeWrite);
        };
        var reader = cache(intercepted);
        var writer = cache(redis);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Map<Long, PostMetricsCache.Counts>> read = pool.submit(() -> reader.get(ids, ignored -> Map.of(id, old)));
            try {
                assertTrue(atWrite.await(10, TimeUnit.SECONDS), "test must intercept the actual Redis fill");
                writer.invalidateAfterCommit(ids);
            } finally { resumeWrite.countDown(); }
            assertEquals(old, read.get(10, TimeUnit.SECONDS).get(id), "in-flight reader may return its old snapshot");
            assertEquals(0, local(reader).estimatedSize(), "rejected shared fill must not enter L1");
            var observer = cache(redis);
            assertEquals(fresh, observer.get(ids, ignored -> Map.of(id, fresh)).get(id),
                    "old fill after peer invalidation must be rejected, not become valid shared data");
        }
    }

    @Test void currentVersionCanFillAndSubsequentInstanceReadsWithoutDatabase() {
        var first = cache(redis);
        assertEquals(fresh, first.get(ids, ignored -> Map.of(id, fresh)).get(id));
        assertNotNull(redis.opsForValue().get(dataKey()));
        assertNotNull(redis.opsForValue().get(epochKey()));
        assertEquals(fresh, cache(redis).get(ids, ignored -> fail("accepted version must be shared")).get(id));
    }

    @Test void twoConcurrentReadersCanFillTheSameCurrentVersion() throws Exception {
        var ready = new CyclicBarrier(2);
        Function<Collection<Long>, Map<Long, PostMetricsCache.Counts>> loader = ignored -> {
            try { ready.await(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
            return Map.of(id, fresh);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> cache(redis).get(ids, loader));
            var second = pool.submit(() -> cache(redis).get(ids, loader));
            assertEquals(fresh, first.get(10, TimeUnit.SECONDS).get(id));
            assertEquals(fresh, second.get(10, TimeUnit.SECONDS).get(id));
        }
        assertEquals(fresh, cache(redis).get(ids, ignored -> fail("concurrent accepted fills must remain usable")).get(id));
    }

    @Test void twoConcurrentInvalidationsFenceTheSameInFlightOldFill() throws Exception {
        var intercepted = new ControlledRedis(factory);
        var atFill = new CountDownLatch(1); var release = new CountDownLatch(1);
        intercepted.beforeFill = () -> { atFill.countDown(); await(release); };
        var reader = cache(intercepted);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var oldRead = pool.submit(() -> reader.get(ids, ignored -> Map.of(id, old)));
            try {
                await(atFill);
                String before = redis.opsForValue().get(epochKey());
                var writerA = pool.submit(() -> cache(redis).invalidateAfterCommit(ids));
                var writerB = pool.submit(() -> cache(redis).invalidateAfterCommit(ids));
                writerA.get(10, TimeUnit.SECONDS); writerB.get(10, TimeUnit.SECONDS);
                assertNotEquals(before, redis.opsForValue().get(epochKey()));
            } finally { release.countDown(); }
            assertEquals(old, oldRead.get(10, TimeUnit.SECONDS).get(id));
            assertEquals(0, local(reader).estimatedSize());
        }
        assertEquals(fresh, cache(redis).get(ids, ignored -> Map.of(id, fresh)).get(id));
    }

    @Test void missingMetadataRejectsOldFillAndNewReadUsesNewEpoch() throws Exception {
        var intercepted = new ControlledRedis(factory);
        var atFill = new CountDownLatch(1); var release = new CountDownLatch(1);
        intercepted.beforeFill = () -> { atFill.countDown(); await(release); };
        var reader = cache(intercepted);
        String before;
        try (var pool = Executors.newSingleThreadExecutor()) {
            var oldRead = pool.submit(() -> reader.get(ids, ignored -> Map.of(id, old)));
            try {
                await(atFill);
                before = redis.opsForValue().get(epochKey());
                assertNotNull(before);
                redis.delete(epochKey());
            } finally { release.countDown(); }
            assertEquals(old, oldRead.get(10, TimeUnit.SECONDS).get(id));
            assertEquals(0, local(reader).estimatedSize());
        }
        assertNull(redis.opsForValue().get(dataKey()));
        assertEquals(fresh, cache(redis).get(ids, ignored -> Map.of(id, fresh)).get(id));
        assertNotEquals(before, redis.opsForValue().get(epochKey()));
    }

    @Test void metadataExpiryCannotMakeSurvivingL2ValueValidUnderANewEpoch() {
        cache(redis).get(ids, ignored -> Map.of(id, old));
        String before = redis.opsForValue().get(epochKey());
        Long ttl = redis.getExpire(epochKey(), TimeUnit.MILLISECONDS);
        assertNotNull(ttl); assertTrue(ttl >= 59000, "metadata has bounded lifetime longer than data");
        redis.expire(epochKey(), Duration.ZERO);
        assertNull(redis.opsForValue().get(epochKey()));
        assertNotNull(redis.opsForValue().get(dataKey()), "test leaves old L2 intact");
        assertEquals(fresh, cache(redis).get(ids, ignored -> Map.of(id, fresh)).get(id));
        assertNotEquals(before, redis.opsForValue().get(epochKey()));
        assertEquals(fresh, cache(redis).get(ids, ignored -> fail("new epoch must cache normally")).get(id));
    }

    @Test void restoredMetadataFromAnotherRedisRunCannotValidateOldData() {
        cache(redis).get(ids, ignored -> Map.of(id, old));
        String oldData = Objects.requireNonNull(redis.opsForValue().get(dataKey()));
        String obsolete = "0000000000000000000000000000000000000000.obsolete";
        redis.opsForValue().set(epochKey(), obsolete, Duration.ofSeconds(60));
        redis.opsForValue().set(dataKey(), obsolete + oldData.substring(oldData.indexOf('|')), Duration.ofSeconds(20));
        assertEquals(fresh, cache(redis).get(ids, ignored -> Map.of(id, fresh)).get(id));
        assertNotEquals(obsolete, redis.opsForValue().get(epochKey()));
    }

    @Test void replayingSameInvalidationCommandDoesNotReuseItsEarlierEpoch() {
        var intercepted = new ControlledRedis(factory);
        var writer = cache(intercepted);
        writer.get(ids, ignored -> Map.of(id, old));
        String first = redis.opsForValue().get(epochKey());
        writer.invalidateAfterCommit(ids);
        String second = redis.opsForValue().get(epochKey());
        assertNotEquals(first, second);
        assertNotNull(intercepted.lastInvalidation);
        intercepted.replay(intercepted.lastInvalidation);
        String third = redis.opsForValue().get(epochKey());
        assertNotEquals(second, third, "retry of an uncertain execution must advance, never resurrect a token");
        assertNotEquals(first, third);
        assertEquals(fresh, cache(redis).get(ids, ignored -> Map.of(id, fresh)).get(id));
    }

    @Test void replayingOldReadInitializationAfterMetadataLossDoesNotReuseEpoch() {
        var intercepted = new ControlledRedis(factory);
        cache(intercepted).get(ids, ignored -> Map.of(id, old));
        String first = redis.opsForValue().get(epochKey());
        redis.delete(epochKey());
        intercepted.replay(intercepted.lastRead);
        assertNotEquals(first, redis.opsForValue().get(epochKey()), "same initialization payload needs a new execution identity");
        assertEquals(fresh, cache(redis).get(ids, ignored -> Map.of(id, fresh)).get(id));
    }

    @Test void l2ReplyDelayedUntilDeliveredInvalidationCannotPromoteIntoL1() throws Exception {
        cache(redis).get(ids, ignored -> Map.of(id, old));
        var intercepted = new ControlledRedis(factory);
        var gotReply = new CountDownLatch(1); var release = new CountDownLatch(1);
        intercepted.afterRead = () -> { gotReply.countDown(); await(release); };
        var reader = cache(intercepted);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var read = pool.submit(() -> reader.get(ids, ignored -> fail("old read already hit L2")));
            try {
                await(gotReply);
                cache(redis).invalidateAfterCommit(ids);
                reader.receiveInvalidation(Long.toString(id));
            } finally { release.countDown(); }
            assertEquals(old, read.get(10, TimeUnit.SECONDS).get(id), "already read snapshot is allowed");
            assertEquals(0, local(reader).estimatedSize(), "delivered invalidation fences promotion of delayed reply");
        }
        intercepted.afterRead = () -> {};
        assertEquals(fresh, reader.get(ids, ignored -> Map.of(id, fresh)).get(id));
    }

    @Test void failedReadScriptFallsBackWithoutPublishingUnconfirmedL1() {
        var intercepted = new ControlledRedis(factory);
        intercepted.beforeRead = () -> { throw new RedisConnectionFailureException("read failure"); };
        var reader = cache(intercepted);
        assertEquals(old, reader.get(ids, ignored -> Map.of(id, old)).get(id));
        assertEquals(0, local(reader).estimatedSize());
        assertEquals(fresh, reader.get(ids, ignored -> Map.of(id, fresh)).get(id));
        assertNull(redis.opsForValue().get(dataKey()));
    }

    @Test void failedFillScriptDoesNotWarmL1OrL2() {
        var intercepted = new ControlledRedis(factory);
        intercepted.beforeFill = () -> { throw new RedisConnectionFailureException("fill failure"); };
        var reader = cache(intercepted);
        assertEquals(old, reader.get(ids, ignored -> Map.of(id, old)).get(id));
        assertEquals(0, local(reader).estimatedSize());
        assertNull(redis.opsForValue().get(dataKey()));
        intercepted.beforeFill = () -> {};
        assertEquals(fresh, reader.get(ids, ignored -> Map.of(id, fresh)).get(id));
        assertEquals(fresh, cache(redis).get(ids, ignored -> fail("recovered fill should work")).get(id));
    }

    @Test void unknownFillResultNeverWarmsL1EvenIfRedisExecutedTheScript() {
        var intercepted = new ControlledRedis(factory);
        intercepted.afterFill = () -> { throw new RedisConnectionFailureException("reply lost after execution"); };
        var reader = cache(intercepted);
        assertEquals(old, reader.get(ids, ignored -> Map.of(id, old)).get(id));
        assertEquals(0, local(reader).estimatedSize());
        assertNotNull(redis.opsForValue().get(dataKey()), "server may have accepted an execution with unknown client result");
        cache(redis).invalidateAfterCommit(ids);
        assertEquals(fresh, cache(redis).get(ids, ignored -> Map.of(id, fresh)).get(id));
    }

    @Test void failedInvalidationScriptStillClearsOwnL1AndAttemptsBroadcast() {
        var intercepted = new ControlledRedis(factory);
        var writer = cache(intercepted);
        writer.get(ids, ignored -> Map.of(id, old));
        intercepted.beforeInvalidation = () -> { throw new RedisConnectionFailureException("invalidation failure"); };
        assertDoesNotThrow(() -> writer.invalidateAfterCommit(ids));
        assertEquals(0, local(writer).estimatedSize());
        assertEquals(1, intercepted.broadcasts.get(), "fence failure must not suppress independent broadcast attempt");
    }

    @Test void failedBroadcastDoesNotUndoSuccessfulVersionAdvance() {
        var intercepted = new ControlledRedis(factory);
        var writer = cache(intercepted);
        writer.get(ids, ignored -> Map.of(id, old));
        String before = redis.opsForValue().get(epochKey());
        intercepted.failBroadcast = true;
        assertDoesNotThrow(() -> writer.invalidateAfterCommit(ids));
        assertNotEquals(before, redis.opsForValue().get(epochKey()));
        assertNull(redis.opsForValue().get(dataKey()));
        assertEquals(0, local(writer).estimatedSize());
        assertEquals(fresh, cache(redis).get(ids, ignored -> Map.of(id, fresh)).get(id));
    }

    @Test void databaseFailureRemainsAnErrorAndDoesNotInventCounts() {
        var failure = new IllegalStateException("database failure");
        var reader = cache(redis);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> reader.get(ids, ignored -> { throw failure; })));
        assertEquals(0, local(reader).estimatedSize());
        assertNull(redis.opsForValue().get(dataKey()));
    }

    @Test void partialBatchHitsAndDuplicatesUseOneReadAndOneFillScript() {
        long second = id == Long.MAX_VALUE ? id - 1 : id + 1;
        long third = id >= Long.MAX_VALUE - 2 ? id - 2 : id + 2;
        ownedIds.add(second); ownedIds.add(third);
        var intercepted = new ControlledRedis(factory);
        var reader = cache(intercepted);
        reader.get(ids, ignored -> Map.of(id, old));
        cache(redis).get(List.of(second), ignored -> Map.of(second, fresh));
        intercepted.reads.set(0); intercepted.fills.set(0);
        var loads = new AtomicInteger();
        var result = reader.get(List.of(id, second, second, third, third), missing -> {
            assertEquals(List.of(third), new ArrayList<>(missing));
            loads.incrementAndGet(); return Map.of(third, fresh);
        });
        assertEquals(Map.of(id, old, second, fresh, third, fresh), result);
        assertEquals(1, loads.get());
        assertEquals(1, intercepted.reads.get()); assertEquals(1, intercepted.fills.get());
    }

    @Test void threeModesAgreeWhileOnlyEnabledCacheUsesVersionScripts() {
        for (var mode : List.of(List.of(false, false), List.of(true, false), List.of(true, true))) {
            redis.delete(List.of(dataKey(), epochKey()));
            var intercepted = new ControlledRedis(factory);
            var reader = new PostMetricsCache(intercepted, mode.get(0), 20, 0, mode.get(1), 10000, 2000, new SimpleMeterRegistry());
            var loads = new AtomicInteger();
            Function<Collection<Long>, Map<Long, PostMetricsCache.Counts>> loader = ignored -> {
                loads.incrementAndGet(); return Map.of(id, fresh);
            };
            assertEquals(fresh, reader.get(ids, loader).get(id));
            assertEquals(fresh, reader.get(ids, loader).get(id));
            if (!mode.get(0)) { assertEquals(2, loads.get()); assertEquals(0, intercepted.reads.get()); assertNull(redis.opsForValue().get(epochKey())); }
            else if (!mode.get(1)) { assertEquals(1, loads.get()); assertEquals(2, intercepted.reads.get()); }
            else { assertEquals(1, loads.get()); assertEquals(1, intercepted.reads.get()); }
        }
    }

    @Test void expiredAbsoluteDeadlineIsRejectedByRedisAndNeverWarmsL1() {
        var meters = new SimpleMeterRegistry();
        var reader = new PostMetricsCache(redis, true, 1, 0, true, 10000, 2000, meters,
                Clock.offset(Clock.systemUTC(), Duration.ofSeconds(-2)), Ticker.systemTicker());
        assertEquals(old, reader.get(ids, ignored -> Map.of(id, old)).get(id));
        assertNull(redis.opsForValue().get(dataKey()));
        assertEquals(0, local(reader).estimatedSize());
        assertEquals(1, Objects.requireNonNull(meters.find("post.metrics.fence.rejected").tag("reason", "expired").counter()).count());
    }

    @Test void delayedL2ReplyCannotStartANewFullL1Lifetime() {
        cache(redis).get(ids, ignored -> Map.of(id, old));
        var clock = new MutableClock();
        var intercepted = new ControlledRedis(factory);
        intercepted.afterRead = () -> clock.advanceMillis(2500);
        var reader = new PostMetricsCache(intercepted, true, 20, 0, true, 10000, 2000, new SimpleMeterRegistry(),
                clock, () -> clock.millis() * 1_000_000);
        assertEquals(old, reader.get(ids, ignored -> fail("captured valid L2 reply")).get(id));
        assertEquals(0, local(reader).estimatedSize(), "L1 admission budget begins before the delayed Redis request");
    }

    private PostMetricsCache cache(StringRedisTemplate transport) {
        return new PostMetricsCache(transport, true, 20, 0, true, 10000, 2000, new SimpleMeterRegistry());
    }

    private String dataKey() { return "xinkelink:cache:v3:post-metrics:" + id; }
    private String epochKey() { return "xinkelink:cache:v3:post-metrics:generation:" + id; }
    private static Cache<?, ?> local(PostMetricsCache cache) {
        return (Cache<?, ?>) Objects.requireNonNull(ReflectionTestUtils.getField(cache, "l1"));
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "controlled Redis operation was never released"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private record ScriptCall(RedisScript<?> script, List<String> keys, Object[] args) {}
    private static final class ControlledRedis extends StringRedisTemplate {
        Runnable beforeRead = () -> {}, afterRead = () -> {}, beforeFill = () -> {}, afterFill = () -> {};
        Runnable beforeInvalidation = () -> {};
        ScriptCall lastRead, lastInvalidation;
        final AtomicInteger broadcasts = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger(), fills = new AtomicInteger();
        boolean failBroadcast;
        ControlledRedis(LettuceConnectionFactory factory) { super(factory); }
        @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            String source = script.getScriptAsString();
            if (source.contains("post-metrics-read")) {
                reads.incrementAndGet();
                lastRead = new ScriptCall(script, List.copyOf(keys), args.clone());
                beforeRead.run(); T result = super.execute(script, keys, args); afterRead.run(); return result;
            }
            if (source.contains("post-metrics-fill")) {
                fills.incrementAndGet();
                beforeFill.run(); T result = super.execute(script, keys, args); afterFill.run(); return result;
            }
            if (source.contains("post-metrics-invalidate")) {
                lastInvalidation = new ScriptCall(script, List.copyOf(keys), args.clone());
                beforeInvalidation.run();
            }
            return super.execute(script, keys, args);
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object replay(ScriptCall call) { return super.execute((RedisScript) call.script, call.keys, call.args); }
        @Override public Long convertAndSend(String destination, Object message) {
            broadcasts.incrementAndGet();
            if (failBroadcast) throw new RedisConnectionFailureException("broadcast failure");
            return super.convertAndSend(destination, message);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(System.currentTimeMillis());
        void advanceMillis(long amount) { millis.addAndGet(amount); }
        @Override public long millis() { return millis.get(); }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
