package cn.xinkelink.notification.mq;

import java.time.Instant;
import java.util.*;

/** Versioned internal intent, deliberately containing no text, image URL or public identity projection. */
public record InteractionEvent(String eventId,int version,String naturalKey,long sequence,String kind,
        String targetType,long targetId,long postId,long actorId,List<Long> recipients,boolean cancelled,Instant occurredAt) {
    public InteractionEvent { recipients=recipients==null?List.of():List.copyOf(recipients); }
    public void validate() {
        if(version!=1||eventId==null||!UUID.fromString(eventId).toString().equals(eventId)||sequence<1||targetId<1||postId<1||actorId<1||occurredAt==null)
            throw new IllegalArgumentException("INVALID_PROTOCOL");
        String expected;
        switch(kind) {
            case "post_like","post_favorite" -> {if(!"post".equals(targetType)||postId!=targetId)throw new IllegalArgumentException("INVALID_TARGET");expected=kind+":"+targetId+":"+actorId;}
            case "comment_like" -> {if(!"comment".equals(targetType))throw new IllegalArgumentException("INVALID_TARGET");expected=kind+":"+targetId+":"+actorId;}
            case "comment","reply" -> {if(!"comment".equals(targetType)||cancelled)throw new IllegalArgumentException("INVALID_TARGET");expected="comment:"+targetId;}
            default -> throw new IllegalArgumentException("INVALID_KIND");
        }
        if(!expected.equals(naturalKey)||recipients.size()>2||new HashSet<>(recipients).size()!=recipients.size()
                ||recipients.stream().anyMatch(id->id==null||id<1||id==actorId))throw new IllegalArgumentException("INVALID_INTENT");
    }
}
