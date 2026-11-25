package cn.xinkelink.statistics;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service @Profile("auth")
public class ViewJournal {
    public record Claim(String eventId,long postId,String payload,String token,int attempts) {}
    private final JdbcTemplate jdbc; private final ViewStatisticsProperties config;
    public ViewJournal(JdbcTemplate jdbc,ViewStatisticsProperties config){this.jdbc=jdbc;this.config=config;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public List<Claim> claim() {
        var expired=jdbc.queryForList("SELECT event_id FROM xl_view_receipt WHERE state='LEASED' AND lease_until<=UTC_TIMESTAMP(3) ORDER BY lease_until,event_id LIMIT ? FOR UPDATE SKIP LOCKED",String.class,config.getBatchSize());
        for(String id:expired)jdbc.update("UPDATE xl_view_receipt SET state='READY',lease_token=NULL,lease_until=NULL,next_attempt_at=UTC_TIMESTAMP(3),last_error='LEASE_EXPIRED',updated_at=UTC_TIMESTAMP(3) WHERE event_id=?",id);
        var claims=jdbc.query("SELECT event_id,post_id,payload,attempts FROM xl_view_receipt WHERE state='READY' AND next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY next_attempt_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED",(rs,n)->new Claim(rs.getString(1),rs.getLong(2),rs.getString(3),UUID.randomUUID().toString(),rs.getInt(4)+1),config.getBatchSize());
        for(int i=0;i<claims.size();i++){var c=claims.get(i);long budget=config.getLeaseMillis()+(long)i*(2L*config.getSendTimeoutMillis()+1000);
            jdbc.update("UPDATE xl_view_receipt SET state='LEASED',attempts=?,lease_token=?,lease_until=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3) WHERE event_id=?",c.attempts(),c.token(),budget*1000,c.eventId());}
        return claims;
    }
    /** Broker retention is shorter than receipt retention. Re-send missing effects from the trusted journal. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int recoverMissing(){
        var ids=jdbc.queryForList("""
            SELECT r.event_id FROM xl_view_receipt r
            WHERE r.state='SENT' AND r.updated_at<=TIMESTAMPADD(SECOND,-60,UTC_TIMESTAMP(3))
              AND NOT EXISTS(SELECT 1 FROM xl_view_event e WHERE e.event_id=r.event_id)
            ORDER BY r.occurred_at,r.event_id LIMIT ? FOR UPDATE SKIP LOCKED
            """,String.class,config.getBatchSize());
        for(String id:ids)jdbc.update("UPDATE xl_view_receipt SET state='READY',next_attempt_at=UTC_TIMESTAMP(3),last_error='MISSING_EFFECT',updated_at=UTC_TIMESTAMP(3) WHERE event_id=?",id);
        return ids.size();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean finish(Claim c,String outcome) {
        if(!Set.of("OK","FAILED","UNKNOWN").contains(outcome))throw new IllegalArgumentException("Invalid send outcome");
        long delay=Math.min(60000L,500L*(1L<<Math.min(c.attempts()-1,16)));
        return jdbc.update("""
            UPDATE xl_view_receipt SET state=?,lease_token=NULL,lease_until=NULL,last_error=?,next_attempt_at=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3)
            WHERE event_id=? AND state='LEASED' AND lease_token=? AND lease_until>UTC_TIMESTAMP(3)
            """,outcome.equals("OK")?"SENT":"READY",outcome,delay*1000,c.eventId(),c.token())==1;
    }
    /** Protected offline operation: same immutable bytes, ID and event time. Active leases cannot be stolen. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean replay(String id){return jdbc.update("UPDATE xl_view_receipt SET state='READY',next_attempt_at=UTC_TIMESTAMP(3),last_error='REPLAY',updated_at=UTC_TIMESTAMP(3) WHERE event_id=? AND state='SENT'",id)==1;}
}
