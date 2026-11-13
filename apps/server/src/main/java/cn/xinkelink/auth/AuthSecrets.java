package cn.xinkelink.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

final class AuthSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private AuthSecrets() {}

    static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("运行环境缺少SHA-256"); }
    }

    static boolean matches(String value, byte[] expected) {
        return value != null && expected != null && MessageDigest.isEqual(hash(value), expected);
    }
}
