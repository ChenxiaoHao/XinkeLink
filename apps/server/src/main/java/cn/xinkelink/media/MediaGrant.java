package cn.xinkelink.media;

import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/** 独立用途派生密钥；授权内容加密，链接不泄露匿名作者或会话标识。 */
@Component
@Profile("auth")
final class MediaGrant {
    record Claim(long imageId, Instant expiresAt, String parentType, long parentId, long userId, long sessionId) {}
    private final byte[] signingKey;
    private final byte[] encryptionKey;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    MediaGrant(TokenProperties properties, Clock clock) {
        byte[] secret = Base64.getDecoder().decode(properties.secretBase64());
        this.signingKey = hmac(secret, "xinkelink.media.grant.sign.v1".getBytes(StandardCharsets.US_ASCII));
        this.encryptionKey = hmac(secret, "xinkelink.media.grant.encrypt.v1".getBytes(StandardCharsets.US_ASCII));
        this.clock = clock;
    }
    String issue(Claim claim) {
        String data = claim.imageId + ":" + claim.expiresAt.getEpochSecond() + ":" + claim.parentType + ":"
                + claim.parentId + ":" + claim.userId + ":" + claim.sessionId;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.US_ASCII));
            String payload = encode(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
            return payload + "." + encode(hmac(signingKey, payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Media signing unavailable"); }
    }
    Claim verify(String token, long imageId) {
        try {
            if (token == null || token.length() > 1024) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(decode(parts[1]), hmac(signingKey, parts[0].getBytes(StandardCharsets.US_ASCII))))
                throw new IllegalArgumentException();
            ByteBuffer encrypted = ByteBuffer.wrap(decode(parts[0]));
            byte[] nonce = new byte[12]; encrypted.get(nonce);
            byte[] body = new byte[encrypted.remaining()]; encrypted.get(body);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            String[] fields = new String(cipher.doFinal(body), StandardCharsets.US_ASCII).split(":", -1);
            if (fields.length != 6) throw new IllegalArgumentException();
            Claim claim = new Claim(Long.parseLong(fields[0]), Instant.ofEpochSecond(Long.parseLong(fields[1])), fields[2],
                    Long.parseLong(fields[3]), Long.parseLong(fields[4]), Long.parseLong(fields[5]));
            if (claim.imageId != imageId || !claim.expiresAt.isAfter(clock.instant())
                    || claim.expiresAt.isAfter(clock.instant().plusSeconds(60))) throw new IllegalArgumentException();
            return claim;
        } catch (RuntimeException | GeneralSecurityException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }
    private static byte[] hmac(byte[] key, byte[] data) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data); }
        catch (GeneralSecurityException exception) { throw new IllegalStateException("Media signing unavailable"); }
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] decode(String text) { return Base64.getUrlDecoder().decode(text); }
}
