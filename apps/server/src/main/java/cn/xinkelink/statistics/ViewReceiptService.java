package cn.xinkelink.statistics;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.forum.ForumMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Service @Profile("auth")
public class ViewReceiptService {
    private final JdbcTemplate jdbc; private final ContentSupport support; private final ForumMapper forum;
    private final VisitorCredentials credentials; private final ViewStatisticsProperties config; private final JsonMapper json; private final MeterRegistry meters;
    public record Input(int version,String eventId,String postId,String anonymousCredential) {
        @Override public String toString(){return "ViewInput[REDACTED]";}
    }
    public ViewReceiptService(JdbcTemplate jdbc,ContentSupport support,ForumMapper forum,VisitorCredentials credentials,ViewStatisticsProperties config,JsonMapper json,MeterRegistry meters){
        this.jdbc=jdbc;this.support=support;this.forum=forum;this.credentials=credentials;this.config=config;this.json=json;this.meters=meters;
    }
    /** The Spring transaction commits before the controller emits 202. No Kafka or Redis call lies in this boundary. */
    @Transactional
    public Map<String,Object> receive(Input input,AuthPrincipal actor) {
        if(!config.isEnabled())throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        if(input==null||input.version()!=1||input.eventId()==null||!input.eventId().matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))invalid();
        long postId=ContentSupport.id(input.postId());
        String visitor;
        if(actor!=null) { if(!"user".equals(actor.actorType())||input.anonymousCredential()!=null)invalid(); visitor=credentials.user(support.require(actor,null)); }
        else visitor=credentials.anonymous(input.anonymousCredential());
        var existing=jdbc.query("SELECT post_id,visitor_key,occurred_at FROM xl_view_receipt WHERE event_id=?",(rs,n)->new Stored(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant()),input.eventId());
        if(!existing.isEmpty()) return accepted(input.eventId(),same(existing.getFirst(),postId,visitor));
        var post=forum.lockPost(postId);
        if(post==null||post.deletedAt()!=null||!"visible".equals(post.status())||!"normal".equals(post.reviewState()))throw new BusinessException(ErrorCode.TARGET_UNAVAILABLE);
        Instant at=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",Timestamp.class).toInstant();
        var event=new ViewEvent(input.eventId(),1,"public_post_detail",postId,visitor,at);event.validate();
        String payload=json.writeValueAsString(event); if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2048)invalid();
        boolean inserted=true;
        try { jdbc.update("""
            INSERT INTO xl_view_receipt(event_id,digest,post_id,visitor_key,occurred_at,payload,state,attempts,next_attempt_at,created_at,updated_at)
            VALUES(?,?,?,?,?,?,'READY',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
            """,event.eventId(),ViewEvent.digest(payload),postId,visitor,Timestamp.from(at),payload);
        } catch(DuplicateKeyException collision) { inserted=false; }
        var stored=jdbc.queryForObject("SELECT post_id,visitor_key,occurred_at FROM xl_view_receipt WHERE event_id=? FOR UPDATE",(rs,n)->new Stored(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant()),input.eventId());
        Instant acceptedAt=same(stored,postId,visitor);
        if(inserted)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){meters.counter("xinkelink.view.receipt","result","NEW").increment();}});
        return accepted(input.eventId(),acceptedAt);
    }
    private record Stored(long post,String visitor,Instant at){}
    private static Instant same(Stored s,long p,String v){if(s.post()!=p||!s.visitor().equals(v))throw new BusinessException(ErrorCode.DUPLICATE_REQUEST_CONFLICT);return s.at();}
    private static Map<String,Object> accepted(String id,Instant at){return ContentSupport.map("accepted",true,"eventId",id,"occurredAt",at);}
    private static void invalid(){throw new BusinessException(ErrorCode.VALIDATION_FAILED);}
}
