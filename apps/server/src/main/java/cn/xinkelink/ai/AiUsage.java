package cn.xinkelink.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Profile("auth")
public class AiUsage {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(AiUsage.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AiUsage(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    record Totals(LocalDate date, long tokens, long calls, long unreportedCalls) {}
    record Event(Instant time, String action, String stage, String model, boolean success, Long tokens, Integer elapsedMs) {}

    Instant now() { return clock.instant(); }

    void call(AiRuntime.Stage stage, String model, Instant started, long nanos, boolean success, Long tokens) {
        int elapsed = (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos));
        record(started, "call", stage, model, success, tokens, elapsed);
    }

    void change(String action, AiRuntime.Stage stage, String model) {
        record(now(), action, stage, model, true, null, null);
    }

    private void record(Instant started, String action, AiRuntime.Stage stage, String model, boolean success,
                        Long tokens, Integer elapsed) {
        try {
            jdbc.update("""
                    INSERT INTO xl_ai_usage (usage_day, recorded_at, action, stage, model, success, tokens, elapsed_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, started.atZone(BUSINESS_ZONE).toLocalDate(), LocalDateTime.ofInstant(started, ZoneOffset.UTC),
                    action, stage == null ? null : stage.id(), model, success, tokens, elapsed);
        } catch (DataAccessException ignored) {
            // Metrics should never replace a valid provider answer with an error.
            log.warn("Could not persist Link AI usage");
        }
    }

    Totals today() {
        LocalDate date = now().atZone(BUSINESS_ZONE).toLocalDate();
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(tokens),0) AS tokens, COUNT(*) AS calls, COUNT(*)-COUNT(tokens) AS missing
                FROM xl_ai_usage WHERE usage_day=? AND action='call'
                """, (row, index) -> new Totals(date, row.getLong("tokens"), row.getLong("calls"), row.getLong("missing")), date);
    }

    List<Event> recent() {
        return jdbc.query("""
                SELECT recorded_at, action, stage, model, success, tokens, elapsed_ms
                FROM xl_ai_usage ORDER BY id DESC LIMIT 20
                """, (row, index) -> new Event(row.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getString("action"), row.getString("stage"), row.getString("model"), row.getBoolean("success"),
                row.getObject("tokens", Long.class), row.getObject("elapsed_ms", Integer.class)));
    }
}
