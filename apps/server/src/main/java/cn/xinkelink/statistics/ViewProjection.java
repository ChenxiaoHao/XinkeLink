package cn.xinkelink.statistics;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

/** Exact seven-calendar-day public counts. Redis is an absolute, disposable query projection. */
public class ViewProjection {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final long SNAPSHOT_TTL_SECONDS = 60;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Compare decimal strings rather than Lua doubles: BIGINT generations can exceed 2^53.
    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>("""
            local previous = redis.call('GET', KEYS[2])
            local incoming = ARGV[1]
            if previous and (#previous > #incoming or (#previous == #incoming and previous >= incoming)) then
                return 0
            end
            redis.call('SET', KEYS[2], incoming)
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """, Long.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final Clock clock;
    private final String snapshotKey, generationKey;

    public record Entry(long postId, long pv, long uv) {}
    public record Snapshot(long generation, LocalDate startDate, LocalDate endDate, Instant updatedAt,
                           List<Entry> entries, boolean available) {
        public Snapshot { entries = entries == null ? List.of() : List.copyOf(entries); }
    }

    public ViewProjection(JdbcTemplate jdbc, StringRedisTemplate redis, TransactionTemplate transactions,
                          MeterRegistry meters, String namespace, Clock clock) {
        if (namespace == null || !namespace.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid view projection namespace");
        this.jdbc = jdbc; this.redis = redis; this.meters = meters; this.clock = clock;
        // Do not inherit a caller's business transaction; allocation is durable before Redis publication.
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(10);
        this.snapshotKey = "xl:view:{" + namespace + "}:ranking";
        this.generationKey = "xl:view:{" + namespace + "}:generation";
    }

    public ViewProjection(JdbcTemplate jdbc, StringRedisTemplate redis, TransactionTemplate transactions,
                          MeterRegistry meters, String namespace) {
        this(jdbc, redis, transactions, meters, namespace, Clock.systemUTC());
    }

    /** Called periodically even without new events, so midnight expiry and lost Redis data self-repair. */
    public boolean project() {
        Timer.Sample sample = Timer.start(meters);
        String result = "error";
        try {
            Snapshot snapshot = capture();
            boolean applied = publish(snapshot);
            result = applied ? "applied" : "stale";
            return applied;
        } finally {
            meters.counter("view.projection.runs", "result", result).increment();
            sample.stop(meters.timer("view.projection.duration", "result", result));
        }
    }

    Snapshot capture() {
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.update("INSERT INTO xl_view_projection(id,generation) VALUES(1,0) ON DUPLICATE KEY UPDATE generation=generation");
            Long previous = jdbc.queryForObject("SELECT generation FROM xl_view_projection WHERE id=1 FOR UPDATE", Long.class);
            long generation = Math.addExact(Objects.requireNonNull(previous), 1L);
            jdbc.update("UPDATE xl_view_projection SET generation=? WHERE id=1", generation);
            LocalDate end = LocalDate.now(clock.withZone(ZONE)), start = end.minusDays(6);
            // One statement gives one committed MySQL snapshot for PV and cross-day DISTINCT UV.
            List<Entry> entries = jdbc.query("""
                    SELECT d.post_id,d.pv,v.uv FROM
                      (SELECT post_id,SUM(pv) AS pv FROM xl_view_daily
                       WHERE view_date>=? AND view_date<=? GROUP BY post_id) d
                    JOIN
                      (SELECT post_id,COUNT(DISTINCT visitor_key) AS uv FROM xl_view_visitor_day
                       WHERE view_date>=? AND view_date<=? GROUP BY post_id) v ON v.post_id=d.post_id
                    WHERE d.pv>0 AND v.uv>0 ORDER BY v.uv DESC,d.post_id ASC
                    """, (row, index) -> new Entry(row.getLong("post_id"), row.getLong("pv"), row.getLong("uv")),
                    java.sql.Date.valueOf(start), java.sql.Date.valueOf(end), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
            return new Snapshot(generation, start, end, clock.instant(), entries, true);
        }));
    }

    boolean publish(Snapshot snapshot) {
        String body = JSON.writeValueAsString(snapshot);
        // A second short DB lock fences tasks even if *all* statistics Redis keys were lost.
        // A delayed generation cannot publish after a newer generation was allocated. Holding this
        // lock through the bounded Redis call also prevents allocation racing with its final CAS.
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Long current = jdbc.queryForObject("SELECT generation FROM xl_view_projection WHERE id=1 FOR UPDATE", Long.class);
            if (current == null || current != snapshot.generation()) return false;
            Long applied = redis.execute(REPLACE, List.of(snapshotKey, generationKey),
                    Long.toString(snapshot.generation()), body, Long.toString(SNAPSHOT_TTL_SECONDS));
            if (applied == null) throw unavailable();
            return applied == 1;
        }));
    }

    /** No DB counts or identity data are read on this path. Visibility is checked by the ranking service. */
    public Snapshot read() {
        try {
            String raw = redis.opsForValue().get(snapshotKey);
            if (raw == null) return empty();
            Snapshot snapshot = JSON.readValue(raw, Snapshot.class);
            validate(snapshot);
            LocalDate today = LocalDate.now(clock.withZone(ZONE));
            if (!today.equals(snapshot.endDate()) || snapshot.updatedAt().isBefore(clock.instant().minusSeconds(SNAPSHOT_TTL_SECONDS)))
                return empty();
            return snapshot;
        } catch (RuntimeException ignored) {
            meters.counter("view.projection.reads", "result", "error").increment();
            // Connection/parse errors must not escape with credentials or be presented as an empty success.
            throw unavailable();
        }
    }

    private void validate(Snapshot snapshot) {
        if (snapshot == null || !snapshot.available() || snapshot.generation() < 1 || snapshot.startDate() == null
                || snapshot.endDate() == null || !snapshot.startDate().plusDays(6).equals(snapshot.endDate())
                || snapshot.updatedAt() == null || snapshot.updatedAt().isAfter(clock.instant().plusSeconds(5))) throw unavailable();
        Entry previous = null;
        Set<Long> ids = new HashSet<>();
        for (Entry entry : snapshot.entries()) {
            if (entry.postId() < 1 || entry.pv() < 1 || entry.uv() < 1 || entry.uv() > entry.pv() || !ids.add(entry.postId())
                    || previous != null && (previous.uv() < entry.uv() || previous.uv() == entry.uv() && previous.postId() >= entry.postId()))
                throw unavailable();
            previous = entry;
        }
    }

    private Snapshot empty() {
        LocalDate end = LocalDate.now(clock.withZone(ZONE));
        return new Snapshot(0, end.minusDays(6), end, null, List.of(), false);
    }

    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}
