package cn.xinkelink.notification.mq;

import cn.xinkelink.notification.NotificationMapper;
import cn.xinkelink.notification.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.time.Instant;

@Service @Profile("auth")
public class InboxProcessor {
    private final InteractionJournal journal;private final NotificationMapper users;private final NotificationService notices;
    private final JsonMapper json;private final MeterRegistry meters;
    public InboxProcessor(InteractionJournal journal,NotificationMapper users,NotificationService notices,JsonMapper json,MeterRegistry meters){this.journal=journal;this.users=users;this.notices=notices;this.json=json;this.meters=meters;}
    public enum Result {APPLIED,STALE,DUPLICATE}
    /** Called through this Spring bean's proxy. Commit exceptions propagate to the listener before it can acknowledge. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Result process(byte[] body){
        if(body==null||body.length>4096)throw new IllegalArgumentException("INVALID_SIZE");
        InteractionEvent e=json.readValue(body,InteractionEvent.class);e.validate();byte[] hash=InteractionJournal.hash(body);
        var stored=journal.stored(e.eventId());
        if(stored==null||!MessageDigest.isEqual(stored.hash(),hash))throw new IllegalArgumentException("UNTRUSTED_INTENT");
        Set<Long> locked=new TreeSet<>(e.recipients());locked.add(e.actorId());users.lockUsers(locked);
        var stream=journal.lock(e.naturalKey());
        if(journal.received(e.eventId(),hash))return Result.DUPLICATE;
        boolean stale=e.sequence()<=stream.applied()||(!e.cancelled()&&stream.cancelled());
        if(!stale)notices.applyInteraction(e);
        journal.applied(e);journal.receive(e,hash,stale?"STALE":"APPLIED");
        if(!stale)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){
                // Timestamp is captured at the tail of the business transaction, so this includes its remaining commit time.
                meters.timer("xinkelink.notification.intent.to.persistence").record(Duration.between(e.occurredAt(),Instant.now()).abs());
            }
        });
        return stale?Result.STALE:Result.APPLIED;
    }
}
