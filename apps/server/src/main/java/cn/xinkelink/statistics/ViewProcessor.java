package cn.xinkelink.statistics;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;

/** One committed MySQL transaction for dedup, PV and exact membership; Redis is downstream only. */
@Service @Profile("auth")
public class ViewProcessor {
    public enum Result { APPLIED, DUPLICATE }
    private final JdbcTemplate jdbc;private final JsonMapper json;private final Clock clock;
    public ViewProcessor(JdbcTemplate jdbc,JsonMapper json,Clock clock){this.jdbc=jdbc;this.json=json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();this.clock=clock;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Result process(byte[] bytes) {
        if(bytes==null||bytes.length==0||bytes.length>2048)throw new IllegalArgumentException("INVALID_PROTOCOL");
        ViewEvent event;try {event=json.readValue(bytes,ViewEvent.class);event.validate();}catch(RuntimeException e){throw new IllegalArgumentException("INVALID_PROTOCOL");}
        String digest=ViewEvent.digest(bytes);
        var receipts=jdbc.query("SELECT digest,payload FROM xl_view_receipt WHERE event_id=? FOR UPDATE",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},event.eventId());
        if(receipts.isEmpty()||!receipts.getFirst()[0].equals(digest)||!receipts.getFirst()[1].equals(new String(bytes,StandardCharsets.UTF_8)))throw new IllegalArgumentException("RECEIPT_MISMATCH");
        var completed=jdbc.queryForList("SELECT digest FROM xl_view_event WHERE event_id=?",String.class,event.eventId());
        if(!completed.isEmpty()){if(!completed.getFirst().equals(digest))throw new IllegalArgumentException("EVENT_ID_CONFLICT");return Result.DUPLICATE;}
        Instant now=clock.instant();
        // Every retained original is replayable, even after a long outage. Old days cannot enter today's window.
        if(event.occurredAt().isAfter(now.plusSeconds(60)))throw new IllegalArgumentException("EVENT_TIME_INVALID");
        LocalDate day=event.occurredAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        jdbc.update("INSERT INTO xl_view_event(event_id,digest,post_id,visitor_key,occurred_at,processed_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))",event.eventId(),digest,event.postId(),event.visitorKey(),Timestamp.from(event.occurredAt()));
        // The post/day counter serializes distinct visitors for this post/day; unique membership covers repeats.
        jdbc.update("INSERT INTO xl_view_daily(post_id,view_date,pv) VALUES(?,?,1) ON DUPLICATE KEY UPDATE pv=pv+1",event.postId(),java.sql.Date.valueOf(day));
        jdbc.update("INSERT IGNORE INTO xl_view_visitor_day(post_id,view_date,visitor_key) VALUES(?,?,?)",event.postId(),java.sql.Date.valueOf(day),event.visitorKey());
        applied(event);
        return Result.APPLIED;
    }
    protected void applied(ViewEvent event) { /* Extension point is inside the transaction, never an acknowledgement. */ }
}
