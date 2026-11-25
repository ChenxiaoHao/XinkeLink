package cn.xinkelink.forum.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.*;

/** Atomic shared-count protocol. No application value or version is written with an unguarded SET. */
final class PostMetricsFence {
    static final String PREFIX = "xinkelink:cache:v3:post-metrics:";
    // Redis run_id changes on restart even when an old persisted dataset is restored.
    // The nonce, execution time and previous token also make repeated invalidations advance,
    // rather than reinstalling a token after an uncertain result/replayed command.
    private static final String TOKEN = """
            local info = redis.call('INFO', 'server')
            local run = string.match(info, 'run_id:([^%s]+)')
            if not run then return redis.error_reply('Missing Redis process epoch') end
            local time = redis.call('TIME')
            local function fresh(nonce, previous, index)
              return run .. '.' .. redis.sha1hex(nonce .. ':' .. time[1] .. ':' .. time[2] .. ':' .. index .. ':' .. (previous or ''))
            end
            """;
    @SuppressWarnings("rawtypes")
    static final DefaultRedisScript<List> READ = new DefaultRedisScript<>("-- post-metrics-read\n" + TOKEN + """
            local result = {}
            for i=1,#KEYS,2 do
              local token = redis.call('GET', KEYS[i])
              if not token or string.sub(token, 1, #run + 1) ~= run .. '.' then
                token = fresh(ARGV[2], token, i)
                redis.call('SET', KEYS[i], token, 'PX', ARGV[1])
                redis.call('DEL', KEYS[i+1])
              else
                redis.call('PEXPIRE', KEYS[i], ARGV[1])
              end
              local value = redis.call('GET', KEYS[i+1])
              if value and string.sub(value, 1, #token + 1) ~= token .. '|' then
                redis.call('DEL', KEYS[i+1])
                value = false
              end
              table.insert(result, token)
              table.insert(result, value or '')
            end
            return result
            """, List.class);
    @SuppressWarnings("rawtypes")
    static final DefaultRedisScript<List> FILL = new DefaultRedisScript<>("-- post-metrics-fill\n" + TOKEN + """
            local result = {}
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            for i=1,#KEYS,2 do
              local a = ((i-1)/2)*3+1
              local token = redis.call('GET', KEYS[i])
              local remaining = tonumber(ARGV[a+2]) - now
              if not token or token ~= ARGV[a] or string.sub(token, 1, #run + 1) ~= run .. '.' then
                table.insert(result, 0)
              elseif remaining <= 0 then
                table.insert(result, -1)
              else
                redis.call('SET', KEYS[i+1], ARGV[a+1], 'PX', remaining)
                table.insert(result, 1)
              end
            end
            return result
            """, List.class);
    static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("-- post-metrics-invalidate\n" + TOKEN + """
            for i=1,#KEYS,4 do
              local previous = redis.call('GET', KEYS[i])
              redis.call('SET', KEYS[i], fresh(ARGV[2], previous, i), 'PX', ARGV[1])
              redis.call('DEL', KEYS[i+1], KEYS[i+2], KEYS[i+3])
            end
            return 1
            """, Long.class);

    record Snapshot(String token, String value) {}
    record Fill(String token, String value, long deadline) {}
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final long metadataTtlMillis;
    PostMetricsFence(StringRedisTemplate redis, MeterRegistry meters, long metadataTtlMillis) {
        this.redis = redis; this.meters = meters; this.metadataTtlMillis = metadataTtlMillis;
    }

    List<Snapshot> read(List<Long> ids) {
        List<?> raw = execute(READ, pairs(ids), "read", Long.toString(metadataTtlMillis), nonce());
        if (raw == null || raw.size() != ids.size()*2) throw new IllegalStateException("Unconfirmed metrics read");
        List<Snapshot> result = new ArrayList<>();
        for (int i=0; i<raw.size(); i+=2) {
            if (!(raw.get(i) instanceof String token) || !token.matches("[a-f0-9]{40}\\.[a-f0-9]{40}")
                    || !(raw.get(i+1) instanceof String value)) throw new IllegalStateException("Invalid metrics fence response");
            result.add(new Snapshot(token, value));
        }
        return result;
    }

    Set<Long> fill(Map<Long, Fill> entries) {
        List<Long> ids = new ArrayList<>(entries.keySet());
        List<String> args = new ArrayList<>();
        entries.values().forEach(fill -> {
            args.add(fill.token); args.add(fill.value); args.add(Long.toString(fill.deadline));
        });
        List<?> raw = execute(FILL, pairs(ids), "fill", args.toArray());
        if (raw == null || raw.size() != ids.size()) throw new IllegalStateException("Unconfirmed metrics fill");
        Set<Long> accepted = new HashSet<>();
        // Validate the entire response before any caller can promote an entry into L1.
        for (Object value : raw) if (!(value instanceof Long code) || code < -1 || code > 1)
            throw new IllegalStateException("Invalid metrics fill response");
        for (int i=0; i<ids.size(); i++) {
            long code = (Long)raw.get(i);
            if (code == 1) {
                accepted.add(ids.get(i)); meters.counter("post.metrics.fence.accepted").increment();
                meters.counter("post.metrics.redis.fill.keys").increment();
            } else meters.counter("post.metrics.fence.rejected", "reason", code == 0 ? "version" : "expired").increment();
        }
        return accepted;
    }

    void invalidate(List<Long> ids) {
        List<String> keys = new ArrayList<>();
        ids.forEach(id -> {
            keys.add(generationKey(id)); keys.add(key(id));
            keys.add("xinkelink:cache:v2:post-metrics:"+id); keys.add("xinkelink:cache:v1:post-metrics:"+id);
        });
        Long result = execute(INVALIDATE, keys, "invalidate", Long.toString(metadataTtlMillis), nonce());
        if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("Unconfirmed metrics invalidation");
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, String operation, Object... args) {
        var sample = Timer.start(meters);
        meters.counter("post.metrics.redis.script.calls", "operation", operation).increment();
        try { return redis.execute(script, keys, args); }
        finally { sample.stop(meters.timer("post.metrics.redis.script", "operation", operation)); }
    }
    private static List<String> pairs(List<Long> ids) {
        List<String> keys = new ArrayList<>();
        ids.forEach(id -> { keys.add(generationKey(id)); keys.add(key(id)); });
        return keys;
    }
    static String key(Long id) { return PREFIX+id; }
    static String generationKey(Long id) { return PREFIX+"generation:"+id; }
    private static String nonce() { return UUID.randomUUID().toString(); }
}
