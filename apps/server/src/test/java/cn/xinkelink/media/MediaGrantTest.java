package cn.xinkelink.media;

import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class MediaGrantTest {
    @Test
    void grantIsBoundToImageExpiresAtSixtySecondsAndRejectsTampering() {
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        var keys = new TokenProperties(Base64.getEncoder().encodeToString(new byte[32]), "test");
        var grants = new MediaGrant(keys, Clock.fixed(now, ZoneOffset.UTC));
        String token = grants.issue(new MediaGrant.Claim(18, now.plusSeconds(60), "post", 34, 0, 0));
        assertEquals(18, grants.verify(token, 18).imageId());
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(BusinessException.class, () -> grants.verify(token, 19)).code());
        assertThrows(BusinessException.class, () -> grants.verify(token + "x", 18));
        var expired = new MediaGrant(keys, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
        assertThrows(BusinessException.class, () -> expired.verify(token, 18));
        byte[] distinct = new byte[32]; distinct[0] = 1;
        String otherKey = Base64.getEncoder().encodeToString(distinct);
        assertThrows(BusinessException.class, () -> new MediaGrant(new TokenProperties(otherKey, "test"), Clock.fixed(now, ZoneOffset.UTC)).verify(token, 18));
    }
}
