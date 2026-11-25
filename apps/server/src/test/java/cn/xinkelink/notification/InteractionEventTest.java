package cn.xinkelink.notification;

import cn.xinkelink.notification.mq.InteractionEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InteractionEventTest {
    @Test void roundTripKeepsStableIdAndOnlyIntentFields() {
        var e=event("post_like",false);
        var json=JsonMapper.builder().build();
        String body=json.writeValueAsString(e);
        assertEquals(e,json.readValue(body,InteractionEvent.class));
        assertFalse(body.contains("content"));assertFalse(body.contains("nickname"));assertFalse(body.contains("image"));
        assertEquals("post_like:10:2",e.naturalKey());
        assertDoesNotThrow(e::validate);
    }
    @Test void validatesProtocolAndNaturalKeyInsteadOfTrustingBrokerId() {
        var e=event("post_like",false);
        assertThrows(IllegalArgumentException.class,()->new InteractionEvent(e.eventId(),2,e.naturalKey(),1,"post_like","post",10,10,2,List.of(1L),false,e.occurredAt()).validate());
        assertThrows(IllegalArgumentException.class,()->new InteractionEvent("broker-id",1,e.naturalKey(),1,"post_like","post",10,10,2,List.of(1L),false,e.occurredAt()).validate());
        assertThrows(IllegalArgumentException.class,()->new InteractionEvent(e.eventId(),1,"post_like:11:2",1,"post_like","post",10,10,2,List.of(1L),false,e.occurredAt()).validate());
    }
    @Test void recipientsMustBeDistinctBoundedAndExcludeActor() {
        for(var recipients:List.of(List.of(1L,1L),List.of(2L),List.of(1L,3L,4L),List.of(-1L)))
            assertThrows(IllegalArgumentException.class,()->new InteractionEvent(UUID.randomUUID().toString(),1,"post_like:10:2",1,"post_like","post",10,10,2,recipients,false,Instant.now()).validate());
    }
    static InteractionEvent event(String kind,boolean cancelled) {
        return new InteractionEvent(UUID.randomUUID().toString(),1,kind+":10:2",1,kind,"post",10,10,2,List.of(1L),cancelled,Instant.now());
    }
}
