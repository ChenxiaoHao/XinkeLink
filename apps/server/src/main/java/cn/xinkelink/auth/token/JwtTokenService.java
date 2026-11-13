package cn.xinkelink.auth.token;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public final class JwtTokenService implements JwtDecoder {
    public static final long ACCESS_SECONDS = 900;
    private static final Set<String> CLAIMS = Set.of(
            "iss", "sub", "sid", "actorType", "aud", "iat", "exp", "jti");

    private final String issuer;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    public JwtTokenService(TokenProperties properties, Clock clock) {
        if (properties == null || properties.issuer() == null || properties.issuer().isBlank() || clock == null) {
            throw new IllegalArgumentException("JWT配置不完整");
        }
        SecretKey key = secretKey(properties.secretBase64());
        this.issuer = properties.issuer();
        this.clock = clock;
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // 统一使用注入时钟严格验时，过期原因以结构化错误交给HTTP边界。
        this.decoder.setJwtValidator(this::validate);
    }

    public String issue(String actorType, long subjectId, long sessionId, Instant sessionExpiresAt) {
        String audience = audience(actorType);
        Instant now = clock.instant();
        if (audience == null || subjectId <= 0 || sessionId <= 0 || sessionExpiresAt == null) {
            throw new IllegalArgumentException("令牌身份或会话无效");
        }
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Instant limit = issuedAt.plusSeconds(ACCESS_SECONDS);
        Instant expiresAt = (sessionExpiresAt.isBefore(limit) ? sessionExpiresAt : limit)
                .truncatedTo(ChronoUnit.SECONDS);
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("会话已过期");
        }
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(Long.toString(subjectId))
                .claim("sid", Long.toString(sessionId)).claim("actorType", actorType)
                .audience(List.of(audience)).issuedAt(issuedAt).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString()).build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    @Override
    public Jwt decode(String token) {
        try {
            return decoder.decode(token);
        } catch (JwtValidationException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new JwtValidationException("访问令牌无效", List.of(error("UNAUTHENTICATED")));
        }
    }

    private OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        Instant now = clock.instant();
        Object actor = jwt.getClaims().get("actorType");
        String expectedAudience = actor instanceof String value ? audience(value) : null;
        if (!jwt.getClaims().keySet().equals(CLAIMS)
                || !issuer.equals(jwt.getClaims().get("iss"))
                || !positiveId(jwt.getClaims().get("sub")) || !positiveId(jwt.getClaims().get("sid"))
                || expectedAudience == null || !List.of(expectedAudience).equals(jwt.getAudience())
                || jwt.getId() == null || jwt.getId().isBlank()
                || issuedAt == null || expiresAt == null || issuedAt.isAfter(now)
                || !expiresAt.isAfter(issuedAt) || expiresAt.isAfter(issuedAt.plusSeconds(ACCESS_SECONDS))) {
            return OAuth2TokenValidatorResult.failure(error("UNAUTHENTICATED"));
        }
        if (!expiresAt.isAfter(now)) {
            return OAuth2TokenValidatorResult.failure(error("TOKEN_EXPIRED"));
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static boolean positiveId(Object value) {
        if (!(value instanceof String text) || !text.matches("[1-9][0-9]*")) {
            return false;
        }
        try {
            return Long.parseLong(text) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String audience(String actorType) {
        if ("user".equals(actorType)) {
            return "xinkelink-mini";
        }
        return "admin".equals(actorType) ? "xinkelink-admin" : null;
    }

    private static OAuth2Error error(String code) {
        return new OAuth2Error(code, "TOKEN_EXPIRED".equals(code) ? "访问令牌已过期" : "访问令牌无效", null);
    }

    private static SecretKey secretKey(String encoded) {
        byte[] bytes;
        try {
            bytes = encoded == null ? new byte[0] : Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT密钥必须为有效Base64且至少包含32字节随机值");
        }
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT密钥必须为有效Base64且至少包含32字节随机值");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
