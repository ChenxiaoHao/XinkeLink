package cn.xinkelink.statistics;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ViewEventTest {
    @Test void protocolRoundTripsWithoutPublicOrAuthenticationDetails() {
        var e = new ViewEvent(UUID.randomUUID().toString(),1,"public_post_detail",123,"a".repeat(64),Instant.parse("2026-11-16T01:02:03.456Z"));
        e.validate();
        var json = JsonMapper.builder().build();
        String body = json.writeValueAsString(e);
        assertEquals(e,json.readValue(body,ViewEvent.class));
        for(String forbidden : new String[]{"nickname","content","jwt","image","userId"}) assertFalse(body.contains(forbidden));
        assertEquals(ViewEvent.digest(body),ViewEvent.digest(body));
        assertNotEquals(ViewEvent.digest(body),ViewEvent.digest(body+" "));
    }
    @Test void invalidProtocolIdentityAndClockPrecisionAreRejected() {
        String id=UUID.randomUUID().toString(); Instant at=Instant.parse("2026-11-16T00:00:00Z");
        assertThrows(IllegalArgumentException.class,()->new ViewEvent(id,2,"public_post_detail",1,"a".repeat(64),at).validate());
        assertThrows(IllegalArgumentException.class,()->new ViewEvent(id,1,"private_profile",1,"a".repeat(64),at).validate());
        assertThrows(IllegalArgumentException.class,()->new ViewEvent("broker-id",1,"public_post_detail",1,"a".repeat(64),at).validate());
        assertThrows(IllegalArgumentException.class,()->new ViewEvent(id,1,"public_post_detail",0,"a".repeat(64),at).validate());
        assertThrows(IllegalArgumentException.class,()->new ViewEvent(id,1,"public_post_detail",1,"client-user",at).validate());
        assertThrows(IllegalArgumentException.class,()->new ViewEvent(id,1,"public_post_detail",1,"a".repeat(64),at.plusNanos(1)).validate());
    }
}
