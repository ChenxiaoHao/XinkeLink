package cn.xinkelink.notification.mq;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** All methods use the application's sole MySQL DataSource; network I/O never happens here. */
@Service @Profile("auth")
public class InteractionJournal {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final NotificationMqProperties config;
    public InteractionJournal(JdbcTemplate jdbc,JsonMapper json,NotificationMqProperties config){this.jdbc=jdbc;this.json=json;this.config=config;}
    public record Stream(long sequence,long applied,boolean cancelled){}
    public record Stored(String payload,byte[] hash){}
    public record Claim(String eventId,String payload,String token,int attempts){}
    @Transactional(propagation=Propagation.MANDATORY)
    public InteractionEvent record(String kind,String targetType,long target,long post,long actor,List<Long> recipients,boolean cancel){
        String key=(kind.equals("comment")||kind.equals("reply"))?"comment:"+target:kind+":"+target+":"+actor;
        jdbc.update("INSERT INTO xl_notification_stream(natural_key) VALUES (?) ON DUPLICATE KEY UPDATE natural_key=natural_key",key);
        Stream s=lock(key);long seq=Math.addExact(s.sequence(),1);boolean cancelled=s.cancelled()||cancel;
        jdbc.update("UPDATE xl_notification_stream SET sequence_no=?,cancelled=?,updated_at=UTC_TIMESTAMP(3) WHERE natural_key=?",seq,cancelled,key);
        var e=new InteractionEvent(UUID.randomUUID().toString(),1,key,seq,kind,targetType,target,post,actor,recipients,cancelled,Instant.now());e.validate();return e;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(InteractionEvent e){String body=json.writeValueAsString(e);if(body.length()>4096)throw new IllegalArgumentException("INTENT_TOO_LARGE");
        jdbc.update("INSERT INTO xl_notification_outbox(event_id,natural_key,sequence_no,post_id,payload,payload_hash) VALUES (?,?,?,?,?,?)",e.eventId(),e.naturalKey(),e.sequence(),e.postId(),body,hash(body.getBytes(StandardCharsets.UTF_8)));}
    public Stored stored(String id){return jdbc.query("SELECT payload,payload_hash FROM xl_notification_outbox WHERE event_id=?",(rs,n)->new Stored(rs.getString(1),rs.getBytes(2)),id).stream().findFirst().orElse(null);}
    @Transactional(propagation=Propagation.MANDATORY)
    public Stream lock(String key){return jdbc.queryForObject("SELECT sequence_no,applied_sequence,cancelled FROM xl_notification_stream WHERE natural_key=? FOR UPDATE",(rs,n)->new Stream(rs.getLong(1),rs.getLong(2),rs.getBoolean(3)),key);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void applied(InteractionEvent e){jdbc.update("UPDATE xl_notification_stream SET applied_sequence=GREATEST(applied_sequence,?),updated_at=UTC_TIMESTAMP(3) WHERE natural_key=?",e.sequence(),e.naturalKey());}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean received(String id,byte[] digest){var hashes=jdbc.queryForList("SELECT payload_hash FROM xl_notification_inbox WHERE event_id=?",byte[].class,id);if(hashes.isEmpty())return false;
        if(!MessageDigest.isEqual(hashes.getFirst(),digest))throw new IllegalArgumentException("EVENT_ID_COLLISION");return true;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void receive(InteractionEvent e,byte[] digest,String outcome){jdbc.update("INSERT INTO xl_notification_inbox(event_id,payload_hash,outcome) VALUES (?,?,?)",e.eventId(),digest,outcome);}

    /** Reclaim and claim in indexed, bounded short transactions. A lease token fences every mutation. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public List<Claim> claim(){
        var expired=jdbc.queryForList("SELECT event_id FROM xl_notification_outbox WHERE state='LEASED' AND lease_until<=UTC_TIMESTAMP(3) ORDER BY lease_until,event_id LIMIT ? FOR UPDATE SKIP LOCKED",String.class,config.getBatchSize());
        for(String id:expired)jdbc.update("UPDATE xl_notification_outbox SET state=IF(attempts>=?,'PAUSED','READY'),lease_token=NULL,lease_until=NULL,next_attempt_at=UTC_TIMESTAMP(3),last_result='LEASE_EXPIRED' WHERE event_id=?",config.getMaxAttempts(),id);
        var rows=jdbc.query("SELECT event_id,payload,attempts FROM xl_notification_outbox WHERE state='READY' AND next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY next_attempt_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs,n)->new Claim(rs.getString(1),rs.getString(2),UUID.randomUUID().toString(),rs.getInt(3)+1),config.getBatchSize());
        // Do not start a batch-sized lease clock before sequential network calls: each claim gets enough budget for its batch position.
        for(int index=0;index<rows.size();index++){var c=rows.get(index);long lease=config.getLeaseMillis()+index*(long)config.getSendTimeoutMillis();
            jdbc.update("UPDATE xl_notification_outbox SET state='LEASED',attempts=?,lease_token=?,lease_until=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(3)) WHERE event_id=?",c.attempts(),c.token(),lease*1000,c.eventId());}
        return rows;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean finish(Claim c,String result,String brokerId){
        boolean success="OK".equals(result);long backoff=Math.min(config.getRetryMaxMillis(),config.getRetryInitialMillis()*(1L<<Math.min(c.attempts()-1,20)));
        return jdbc.update("""
            UPDATE xl_notification_outbox SET state=?,lease_token=NULL,lease_until=NULL,last_result=?,broker_message_id=?,
              sent_at=IF(?='SENT',UTC_TIMESTAMP(3),sent_at),next_attempt_at=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(3))
            WHERE event_id=? AND state='LEASED' AND lease_token=? AND lease_until>UTC_TIMESTAMP(3)
            """,success?"SENT":c.attempts()>=config.getMaxAttempts()?"PAUSED":"READY",result,brokerId,success?"SENT":"READY",backoff*1000,c.eventId(),c.token())==1;
    }
    /** Offline/operator compensation, never a public endpoint. The immutable event and ID remain unchanged. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean replay(String eventId){return jdbc.update("UPDATE xl_notification_outbox SET state='READY',attempts=0,next_attempt_at=UTC_TIMESTAMP(3),last_result='REPLAY' WHERE event_id=? AND state IN ('PAUSED','SENT')",eventId)==1;}
    public static byte[] hash(byte[] data){try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
