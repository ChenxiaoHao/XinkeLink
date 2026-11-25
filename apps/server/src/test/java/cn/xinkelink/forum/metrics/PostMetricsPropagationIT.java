package cn.xinkelink.forum.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.RedisConnectionFailureException;

/** Real Redis transport, two independent instances/connections, no database or service data. */
class PostMetricsPropagationIT {
    @Test void twoInstancesReceiveInvalidationAndResubscriptionClearsMissedMessages() throws Exception {
        var firstFactory = new LettuceConnectionFactory("127.0.0.1",16379);
        var secondFactory = new LettuceConnectionFactory("127.0.0.1",16379);
        firstFactory.afterPropertiesSet(); firstFactory.start();
        secondFactory.afterPropertiesSet(); secondFactory.start();
        var firstRedis = new StringRedisTemplate(firstFactory);
        var secondRedis = new StringRedisTemplate(secondFactory);
        var firstMeters = new SimpleMeterRegistry(); var secondMeters = new SimpleMeterRegistry();
        var first = new PostMetricsCache(firstRedis,true,20,0,true,10000,2000,firstMeters);
        var second = new PostMetricsCache(secondRedis,true,20,0,true,10000,2000,secondMeters);
        var configuration = new PostMetricsInvalidation();
        var flakySubscription = spy(firstFactory);
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() <= 2) throw new RedisConnectionFailureException("initial subscription unavailable");
            return firstFactory.getConnection();
        }).when(flakySubscription).getConnection();
        var a = configuration.postMetricsInvalidationContainer(flakySubscription, first);
        var b = configuration.postMetricsInvalidationContainer(secondFactory, second);
        long id = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        var ids = List.of(id);
        try {
            a.start(); b.start();
            await(() -> resets(secondMeters) >= 1);
            await(() -> attempts.get() >= 3 && resets(firstMeters) >= 3);
            var old = Map.of(id,new PostMetricsCache.Counts(1,1));
            assertEquals(old, first.get(ids, ignored -> old));
            assertEquals(old, second.get(ids, ignored -> fail("L2 shared")));
            double before = invalidations(secondMeters);
            double firstBefore = invalidations(firstMeters);
            first.invalidateAfterCommit(ids);
            await(() -> invalidations(secondMeters) > before);
            await(() -> invalidations(firstMeters) >= firstBefore + 3);
            var fresh = Map.of(id,new PostMetricsCache.Counts(2,2));
            first.get(ids, ignored -> fresh);
            assertEquals(fresh,second.get(ids, ignored -> fail("broadcast must evict peer L1")));

            b.stop(); // While offline, Redis Pub/Sub retains no invalidation messages.
            // Unsubscribe clears L1 too. A normal read while disconnected may repopulate it.
            assertEquals(fresh,second.get(ids, ignored -> fresh));
            double firstBeforeOffline = invalidations(firstMeters);
            first.invalidateAfterCommit(ids);
            await(() -> invalidations(firstMeters) >= firstBeforeOffline + 3);
            var newest = Map.of(id,new PostMetricsCache.Counts(3,3));
            first.get(ids, ignored -> newest);
            assertEquals(fresh,second.get(ids, ignored -> fail("offline L1 is briefly stale")));
            double resetBefore = resets(secondMeters);
            b.start();
            await(() -> resets(secondMeters) > resetBefore);
            assertEquals(newest,second.get(ids, ignored -> fail("resubscription must clear missed L1")));
        } finally {
            a.destroy(); b.destroy(); firstRedis.delete(PostMetricsCache.PREFIX+id);
            firstFactory.destroy(); secondFactory.destroy();
            firstMeters.close(); secondMeters.close();
        }
    }
    private static double resets(SimpleMeterRegistry registry) {
        var counter = registry.find("post.metrics.subscription.resets").counter();
        return counter == null ? 0 : counter.count();
    }
    private static double invalidations(SimpleMeterRegistry registry) {
        var counter = registry.find("post.metrics.invalidations").counter();
        return counter == null ? 0 : counter.count();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime()<until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"Real Redis subscription condition timed out");
    }
}
