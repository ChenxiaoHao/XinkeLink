package cn.xinkelink.statistics;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class VisitorCredentialsTest {
    static final String SECRET="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    final Clock now=Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"),ZoneOffset.UTC);
    @Test void signedVisitorCredentialIsVerifiableAcrossServersAndCannotBecomeLoginIdentity() {
        var a=new VisitorCredentials(SECRET,now); var b=new VisitorCredentials(SECRET,now);
        var token=a.issue();
        assertEquals(a.anonymous(token.credential()),b.anonymous(token.credential()));
        assertNotEquals(a.anonymous(token.credential()),a.user(1));
        assertNotEquals(a.user(1),a.user(2));
        assertNotEquals(a.anonymous(token.credential()),a.anonymous(a.issue().credential()));
        assertEquals(now.instant().plus(Duration.ofDays(30)),token.expiresAt());
        assertFalse(token.toString().contains(token.credential()));
    }
    @Test void forgedExpiredFutureAndLoginTokensCannotBeUsedAsAnonymousCredentials() {
        var a=new VisitorCredentials(SECRET,now); String token=a.issue().credential();
        assertThrows(RuntimeException.class,()->a.anonymous(token.substring(0,token.length()-2)+"XX"));
        assertThrows(RuntimeException.class,()->a.anonymous("user:1"));
        assertThrows(RuntimeException.class,()->a.anonymous("ey.fake.jwt"));
        assertThrows(RuntimeException.class,()->new VisitorCredentials(SECRET,Clock.offset(now,Duration.ofDays(31))).anonymous(token));
        assertThrows(RuntimeException.class,()->new VisitorCredentials(SECRET,Clock.offset(now,Duration.ofDays(-1))).anonymous(token));
        assertThrows(RuntimeException.class,()->a.anonymous("x".repeat(513)));
    }
}
