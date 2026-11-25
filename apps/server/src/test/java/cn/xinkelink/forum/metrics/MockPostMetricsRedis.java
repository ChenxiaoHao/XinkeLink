package cn.xinkelink.forum.metrics;

import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit-test transport adapter only. It models script results and retains the existing
 * ValueOperations fault hooks; real Lua atomicity is verified by PostMetricsFenceIT. */
final class MockPostMetricsRedis {
    private static final String RUN = "a".repeat(40);
    private final StringRedisTemplate redis;
    private final ValueOperations<String, String> values;
    private final Map<String, String> stored;
    private final Clock clock;
    final Map<String, String> versions = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    Runnable beforeFill = () -> {};

    MockPostMetricsRedis(StringRedisTemplate redis, ValueOperations<String, String> values,
                         Map<String, String> stored, Clock clock) {
        this.redis = redis; this.values = values; this.stored = stored; this.clock = clock;
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(this::execute);
    }

    void seed(String key, String rawDeadlineAndCounts) {
        String token = versions.computeIfAbsent(generationKey(key), ignored -> next());
        stored.put(key, token + "|" + rawDeadlineAndCounts);
    }

    private Object execute(InvocationOnMock invocation) {
        RedisScript<?> script = invocation.getArgument(0);
        List<String> keys = invocation.getArgument(1);
        Object[] args = (Object[]) invocation.getRawArguments()[2];
        String source = script.getScriptAsString();
        if (source.contains("post-metrics-read")) return read(keys);
        if (source.contains("post-metrics-fill")) return fill(keys, args);
        if (source.contains("post-metrics-invalidate")) return invalidate(keys);
        throw new AssertionError("Unexpected metrics script");
    }

    private List<String> read(List<String> keys) {
        List<String> dataKeys = new ArrayList<>();
        for (int i = 1; i < keys.size(); i += 2) dataKeys.add(keys.get(i));
        // A hook for the preexisting unit tests, not a claim that production Lua uses MGET.
        List<String> found = values.multiGet(dataKeys);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += 2) {
            String token = versions.computeIfAbsent(keys.get(i), ignored -> next());
            String value = found == null || i / 2 >= found.size() ? null : found.get(i / 2);
            if (value != null && !value.startsWith(token + "|")) { stored.remove(keys.get(i + 1)); value = null; }
            result.add(token); result.add(value == null ? "" : value);
        }
        return result;
    }

    private List<Long> fill(List<String> keys, Object[] args) {
        beforeFill.run();
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += 2) {
            int offset = i / 2 * 3;
            String expected = String.valueOf(args[offset]);
            String value = String.valueOf(args[offset + 1]);
            long deadline = Long.parseLong(String.valueOf(args[offset + 2]));
            if (!expected.equals(versions.get(keys.get(i)))) result.add(0L);
            else if (deadline <= clock.millis()) result.add(-1L);
            else {
                values.set(keys.get(i + 1), value, Duration.ofMillis(deadline - clock.millis()));
                result.add(1L);
            }
        }
        return result;
    }

    private Long invalidate(List<String> keys) {
        List<String> dataKeys = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += 4) {
            versions.put(keys.get(i), next());
            dataKeys.addAll(keys.subList(i + 1, i + 4));
        }
        redis.delete(dataKeys);
        return 1L;
    }

    private String next() { return RUN + "." + String.format(Locale.ROOT, "%040x", sequence.incrementAndGet()); }
    private static String generationKey(String key) { return key.replace("post-metrics:", "post-metrics:generation:"); }
}
