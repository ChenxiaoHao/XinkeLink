package cn.xinkelink.statistics;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Set;

@Service @Profile("auth")
public class ViewFailures {
    private final JdbcTemplate jdbc;
    public ViewFailures(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean blockedBefore(String topic,int partition,long offset){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM xl_view_failure WHERE topic=? AND partition_id=? AND state='OPEN' AND record_offset<?)",Boolean.class,topic,partition,offset));}
    public Long firstOpen(String topic,int partition){return jdbc.queryForObject("SELECT MIN(record_offset) FROM xl_view_failure WHERE topic=? AND partition_id=? AND state='OPEN'",Long.class,topic,partition);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void failed(String topic,int partition,long offset,byte[] bytes,RuntimeException failure) {
        String reason=failure instanceof IllegalArgumentException&&Set.of("INVALID_PROTOCOL","RECEIPT_MISMATCH","EVENT_ID_CONFLICT","EVENT_TIME_INVALID","OFFSET_UNAVAILABLE","PRIOR_RECORD_BLOCKED").contains(failure.getMessage())?failure.getMessage():"PROCESSING_FAILED";
        // Never archive arbitrary untrusted bodies, which could be made to contain secrets or content.
        jdbc.update("""
            INSERT INTO xl_view_failure(topic,partition_id,record_offset,digest,reason,attempts,state,payload,created_at,updated_at)
            VALUES(?,?,?,?,?,1,'OPEN',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE reason=VALUES(reason),attempts=attempts+1,state='OPEN',updated_at=UTC_TIMESTAMP(3)
            """,topic,partition,offset,ViewEvent.digest(bytes==null?new byte[0]:bytes),reason,new byte[0]);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void resolved(String topic,int partition,long offset){jdbc.update("UPDATE xl_view_failure SET state='RESOLVED',updated_at=UTC_TIMESTAMP(3) WHERE topic=? AND partition_id=? AND record_offset=? AND state='OPEN'",topic,partition,offset);}
}
