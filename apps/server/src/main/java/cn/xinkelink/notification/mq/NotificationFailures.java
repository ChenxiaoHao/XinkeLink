package cn.xinkelink.notification.mq;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Durable repair evidence, separate from Inbox: a failed attempt is never a successful consumption. */
@Service @Profile("auth")
public class NotificationFailures {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.json.JsonMapper json;
    public NotificationFailures(JdbcTemplate jdbc,tools.jackson.databind.json.JsonMapper json){this.jdbc=jdbc;this.json=json;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(byte[] body,int retries,boolean quarantined){
        if(body==null||body.length>4*1024*1024)throw new IllegalArgumentException("BROKER_MESSAGE_SIZE");
        String eventId=null;
        if(body.length<=4096)try{String candidate=json.readTree(body).path("eventId").asString();if(java.util.UUID.fromString(candidate).toString().equals(candidate))eventId=candidate;}catch(RuntimeException invalid){/* Original bytes/hash remain traceable. */}
        jdbc.update("""
            INSERT INTO xl_notification_failure(message_hash,event_id,body,reason,broker_retries,state)
            VALUES (?,?,?,'PROCESS',?,?) ON DUPLICATE KEY UPDATE attempts=attempts+1,broker_retries=GREATEST(broker_retries,?),
              state=?,last_failed_at=UTC_TIMESTAMP(3),resolved_at=NULL
            """,InteractionJournal.hash(body),eventId,body,retries,quarantined?"QUARANTINED":"RETRY",retries,quarantined?"QUARANTINED":"RETRY");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void resolve(byte[] body){jdbc.update("UPDATE xl_notification_failure SET state='RESOLVED',resolved_at=UTC_TIMESTAMP(3) WHERE message_hash=? AND state<>'RESOLVED'",InteractionJournal.hash(body));}
}
