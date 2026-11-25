package cn.xinkelink.forum.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Shared public counts only. Visibility, identity and viewer state remain live MySQL reads. */
@Component
@Profile("auth")
public class PostMetricsCache {
    static final String PREFIX = PostMetricsFence.PREFIX;
    static final String CHANNEL = "xinkelink:cache:v2:post-metrics:invalidate";
    private static final Pattern VALUE = Pattern.compile("[0-9]{1,19}:[0-9]{1,19}:[0-9]{1,19}");
    private static final Pattern ID = Pattern.compile("[0-9]{1,19}");
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final boolean l1Enabled;
    private final long ttlSeconds, jitterSeconds;
    private final Cache<Long, Entry> l1;
    private final Clock clock;
    private final MeterRegistry meters;
    private final PostMetricsFence shared;
    private DataSource dataSource;
    private final long l1TtlMillis;
    // One bounded generation, rather than an unbounded per-post version map. An unrelated write
    // may conservatively suppress a fill; it cannot introduce stale counts.
    private final Object fence = new Object();
    private long generation;

    public record Counts(long likeCount, long commentCount) {}
    private record Entry(String token, Counts counts, long expiresAt, long localExpiresAt) {}

    @Autowired
    public PostMetricsCache(StringRedisTemplate redis,
            @Value("${xinkelink.cache.post-metrics.enabled:true}") boolean enabled,
            @Value("${xinkelink.cache.post-metrics.ttl-seconds:20}") long ttlSeconds,
            @Value("${xinkelink.cache.post-metrics.jitter-seconds:10}") long jitterSeconds,
            @Value("${xinkelink.cache.post-metrics.l1.enabled:true}") boolean l1Enabled,
            @Value("${xinkelink.cache.post-metrics.l1.maximum-size:10000}") long maximumSize,
            @Value("${xinkelink.cache.post-metrics.l1.ttl-millis:2000}") long l1TtlMillis,
            MeterRegistry meters, DataSource dataSource) {
        this(redis, enabled, ttlSeconds, jitterSeconds, l1Enabled, maximumSize, l1TtlMillis, meters);
        this.dataSource = dataSource;
    }

    public PostMetricsCache(StringRedisTemplate redis, boolean enabled, long ttlSeconds, long jitterSeconds,
            boolean l1Enabled, long maximumSize, long l1TtlMillis, MeterRegistry meters) {
        this(redis, enabled, ttlSeconds, jitterSeconds, l1Enabled, maximumSize, l1TtlMillis,
                meters, Clock.systemUTC(), Ticker.systemTicker());
    }

    /** Kept for existing Redis-only callers/tests. */
    public PostMetricsCache(StringRedisTemplate redis, boolean enabled, long ttlSeconds, long jitterSeconds) {
        this(redis, enabled, ttlSeconds, jitterSeconds, false, 10000, 2000, new SimpleMeterRegistry());
    }

    PostMetricsCache(StringRedisTemplate redis, boolean enabled, long ttlSeconds, long jitterSeconds,
            boolean l1Enabled, long maximumSize, long l1TtlMillis, MeterRegistry meters, Clock clock, Ticker ticker) {
        if (ttlSeconds < 1 || jitterSeconds < 0 || ttlSeconds > Long.MAX_VALUE / 1000 - jitterSeconds
                || maximumSize < 1 || l1TtlMillis < 1) throw new IllegalArgumentException("Invalid post metrics cache limits");
        this.redis = redis; this.enabled = enabled; this.ttlSeconds = ttlSeconds; this.jitterSeconds = jitterSeconds;
        this.l1Enabled = l1Enabled; this.clock = clock; this.meters = meters; this.l1TtlMillis = l1TtlMillis;
        this.shared = new PostMetricsFence(redis, meters, Math.max(60000, Math.multiplyExact((ttlSeconds+jitterSeconds)*1000, 2)));
        this.l1 = Caffeine.newBuilder().maximumSize(maximumSize)
                .expireAfterWrite(Duration.ofMillis(l1TtlMillis)).ticker(ticker).recordStats()
                .evictionListener((Object id, Object entry, com.github.benmanes.caffeine.cache.RemovalCause cause) ->
                        meters.counter("post.metrics.l1.evictions", "cause", cause.name().toLowerCase(Locale.ROOT)).increment())
                .build();
        meters.gauge("post.metrics.l1.size", l1, Cache::estimatedSize);
    }

    public Map<Long, Counts> get(Collection<Long> ids, Function<Collection<Long>, Map<Long, Counts>> loader) {
        if (ids.isEmpty()) return Map.of();
        List<Long> requested = List.copyOf(new LinkedHashSet<>(ids));
        // Never publish uncommitted counts, even to this instance's L1.
        if (!enabled || (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) || !safeSnapshot()) return load(requested, loader);

        long localDeadline = Math.addExact(clock.millis(), l1TtlMillis);
        long observed;
        synchronized (fence) { observed = generation; }
        Map<Long, Counts> result = new LinkedHashMap<>();
        List<Long> l2Ids = new ArrayList<>();
        for (Long id : requested) {
            Entry entry = l1Enabled ? l1.getIfPresent(id) : null;
            if (entry != null && entry.expiresAt > clock.millis() && entry.localExpiresAt > clock.millis()) {
                meters.counter("post.metrics.l1.hit").increment(); result.put(id, entry.counts);
            } else {
                if (l1Enabled) { meters.counter("post.metrics.l1.miss").increment(); l1.invalidate(id); }
                l2Ids.add(id);
            }
        }
        if (l2Ids.isEmpty()) return result;

        List<PostMetricsFence.Snapshot> cached;
        var read = Timer.start(meters);
        meters.counter("post.metrics.redis.read.calls").increment();
        try { cached = shared.read(l2Ids); }
        catch (RuntimeException ignored) {
            error("read");
            result.putAll(load(l2Ids, loader));
            return result; // No confirmed token: never publish the fallback into either cache.
        } finally { read.stop(meters.timer("post.metrics.redis.read")); }
        List<Long> missing = new ArrayList<>();
        Map<Long, String> tokens = new LinkedHashMap<>();
        for (int i = 0; i < l2Ids.size(); i++) {
            Long id = l2Ids.get(i);
            var snapshot = cached.get(i);
            tokens.put(id, snapshot.token());
            Entry entry = decode(snapshot.value(), snapshot.token(), localDeadline);
            if (entry == null) { meters.counter("post.metrics.l2.miss").increment(); missing.add(id); }
            else {
                meters.counter("post.metrics.l2.hit").increment(); result.put(id, entry.counts);
                fillLocal(id, entry, observed);
            }
        }
        if (missing.isEmpty()) return result;

        // Start the validity budget BEFORE loading: slow/in-flight old reads cannot start a new TTL.
        long sourceStart = clock.millis();
        Map<Long, Counts> loaded = load(missing, loader); // DB exceptions deliberately outside Redis handling
        Map<Long, Entry> fills = new LinkedHashMap<>();
        for (Long id : missing) {
            Counts counts = loaded.get(id);
            if (counts == null) continue;
            result.put(id, counts);
            if (counts.likeCount < 0 || counts.commentCount < 0) continue;
            long jitter = jitterSeconds == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
            Entry entry = new Entry(tokens.get(id), counts, Math.addExact(sourceStart, (ttlSeconds + jitter) * 1000), localDeadline);
            synchronized (fence) { if (generation != observed) continue; }
            if (entry.expiresAt <= clock.millis()) continue;
            fills.put(id, entry);
        }
        if (!fills.isEmpty()) {
            Set<Long> accepted = fillRedis(fills, observed);
            accepted.forEach(id -> fillLocal(id, fills.get(id), observed));
        }
        return result;
    }

    private Set<Long> fillRedis(Map<Long, Entry> entries, long observed) {
        synchronized (fence) { if (generation != observed) return Set.of(); }
        var sample = Timer.start(meters);
        meters.counter("post.metrics.redis.fill.calls").increment();
        try {
            Map<Long, PostMetricsFence.Fill> fills = new LinkedHashMap<>();
            entries.forEach((id, entry) -> fills.put(id, new PostMetricsFence.Fill(entry.token, encode(entry), entry.expiresAt)));
            return shared.fill(fills);
        } catch (RuntimeException ignored) {
            error("fill");
            return Set.of(); // A timeout may have executed: absence of explicit acceptance forbids L1.
        } finally { sample.stop(meters.timer("post.metrics.redis.fill")); }
    }

    /** A long-lived RR/Serializable snapshot may predate the Redis token we are about to read.
     * Preserve the caller's transaction, but never publish or consume shared counts from it. */
    private boolean safeSnapshot() {
        if (dataSource == null) return true; // Standalone cache tests use explicit non-database loaders.
        var sample = Timer.start(meters);
        meters.counter("post.metrics.isolation.checks").increment();
        try {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            boolean safe = resource instanceof ConnectionHolder holder
                    && holder.getConnection().getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED;
            if (!safe) meters.counter("post.metrics.isolation.bypass").increment();
            return safe;
        } catch (SQLException | RuntimeException ignored) {
            meters.counter("post.metrics.isolation.bypass").increment();
            return false;
        } finally { sample.stop(meters.timer("post.metrics.isolation.check")); }
    }

    private Map<Long, Counts> load(Collection<Long> ids, Function<Collection<Long>, Map<Long, Counts>> loader) {
        var sample = Timer.start(meters);
        meters.counter("post.metrics.database.loads").increment();
        meters.counter("post.metrics.database.ids").increment(ids.size());
        try { return loader.apply(ids); }
        finally { sample.stop(meters.timer("post.metrics.database.load")); }
    }

    private void fillLocal(Long id, Entry entry, long observed) {
        if (!l1Enabled) return;
        synchronized (fence) {
            if (generation == observed && entry.expiresAt > clock.millis() && entry.localExpiresAt > clock.millis()) l1.put(id, entry);
        }
    }

    public void invalidateAfterCommit(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        List<Long> unique = List.copyOf(new LinkedHashSet<>(ids));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(unique); }
            });
        } else invalidate(unique);
    }

    /** Synchronous CDC boundary: return only after Redis confirms the shared fence advance
     * and count deletion. A failure/unknown result must not be acknowledged by a consumer.
     * Broadcast remains best effort; a confirmed shared invalidation may leave peer L1 values
     * until their short TTL when Pub/Sub is unavailable. */
    public void invalidateConfirmed(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Confirmed post metrics invalidation requires no active transaction");
        }
        invalidateShared(List.copyOf(new LinkedHashSet<>(ids)));
    }

    /** No transport cause is exposed: connector exception messages can contain private details. */
    public static final class InvalidationNotConfirmedException extends IllegalStateException {
        private InvalidationNotConfirmedException() { super("Shared post metrics invalidation was not confirmed"); }
    }

    private void invalidate(List<Long> ids) {
        try { invalidateShared(ids); }
        catch (InvalidationNotConfirmedException ignored) {
            // The database is already committed. Business writes remain best effort.
        }
    }

    private void invalidateShared(List<Long> ids) {
        boolean confirmed = false;
        invalidateLocal(ids);
        // The generation advance and L2 deletion are one Redis operation. Never fall back to plain DEL/SET.
        try { shared.invalidate(ids); confirmed = true; }
        catch (RuntimeException ignored) { error("delete"); }
        finally { invalidateLocal(ids); } // Also fence reads started while shared advancement was in flight.
        // Broadcasting is independent: even a failed/unknown advance must evict peer L1 best effort.
        try { redis.convertAndSend(CHANNEL, String.join(",", ids.stream().map(String::valueOf).toList())); }
        catch (RuntimeException ignored) { error("publish"); }
        if (!confirmed) throw new InvalidationNotConfirmedException();
    }

    private void invalidateLocal(Collection<Long> ids) {
        synchronized (fence) { generation++; l1.invalidateAll(ids); }
        meters.counter("post.metrics.invalidations").increment();
    }

    /** Public counts IDs only; malformed messages are discarded as a whole. No identity payloads. */
    public void receiveInvalidation(String payload) {
        if (payload == null || payload.length() > 65536) return;
        String[] parts = payload.split(",", -1);
        for (String part : parts) if (!ID.matcher(part).matches()) return;
        try { invalidateLocal(Arrays.stream(parts).map(Long::parseLong).toList()); }
        catch (NumberFormatException ignored) { /* reject overflow */ }
    }

    /** Pub/Sub has no offline replay. Every subscription confirmation clears possibly missed L1 entries. */
    public void onSubscriptionRestored() {
        synchronized (fence) { generation++; l1.invalidateAll(); }
        meters.counter("post.metrics.subscription.resets").increment();
    }

    void subscriptionFailed() {
        meters.counter("post.metrics.subscription.failures").increment();
        onSubscriptionRestored();
    }

    private Entry decode(String value, String token, long localDeadline) {
        if (value == null || !value.startsWith(token+"|")) return null;
        value = value.substring(token.length()+1);
        if (!VALUE.matcher(value).matches()) return null;
        try {
            String[] parts = value.split(":");
            long deadline = Long.parseLong(parts[0]);
            long now = clock.millis();
            if (deadline <= now || deadline - now > (ttlSeconds + jitterSeconds) * 1000) return null;
            return new Entry(token, new Counts(Long.parseLong(parts[1]), Long.parseLong(parts[2])), deadline, localDeadline);
        } catch (NumberFormatException ignored) { return null; }
    }
    private static String encode(Entry entry) { return entry.token + "|" + entry.expiresAt + ":" + entry.counts.likeCount + ":" + entry.counts.commentCount; }
    private void error(String operation) { meters.counter("post.metrics.redis.errors", "operation", operation).increment(); }
}
