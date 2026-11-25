package cn.xinkelink.statistics;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Private wire intent. The receipt journal is the sole trusted source of its bytes and time. */
public record ViewEvent(String eventId, int version, String kind, long postId, String visitorKey, Instant occurredAt) {
    public void validate() {
        if(eventId==null || !eventId.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                || !UUID.fromString(eventId).toString().equals(eventId) || version!=1 || !"public_post_detail".equals(kind)
                || postId<=0 || visitorKey==null || !visitorKey.matches("[0-9a-f]{64}") || occurredAt==null
                || occurredAt.getNano()%1_000_000!=0) throw new IllegalArgumentException("INVALID_EVENT");
    }
    public static String digest(String body) { return digest(body.getBytes(StandardCharsets.UTF_8)); }
    public static String digest(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256 unavailable"); }
    }
}
