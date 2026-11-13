package cn.xinkelink.auth.token;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final String ISSUER = "xinkelink-test";
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private byte[] keyBytes;
    private String secret;
    private JwtTokenService tokens;

    @BeforeEach
    void setUp() {
        keyBytes = randomKey(32);
        secret = Base64.getEncoder().encodeToString(keyBytes);
        tokens = new JwtTokenService(new TokenProperties(secret, ISSUER), clock);
    }

    @Test
    void issuesUserTokenWithOnlyRequiredClaimsAndFifteenMinuteLifetime() {
        Jwt token = tokens.decode(tokens.issue("user", 12L, 34L, NOW.plusSeconds(3600)));

        assertThat(token.getClaims()).containsOnlyKeys(
                "iss", "sub", "sid", "actorType", "aud", "iat", "exp", "jti");
        assertThat(token.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(token.getSubject()).isEqualTo("12");
        assertThat(token.getClaimAsString("sid")).isEqualTo("34");
        assertThat(token.getClaimAsString("actorType")).isEqualTo("user");
        assertThat(token.getAudience()).containsExactly("xinkelink-mini");
        assertThat(token.getIssuedAt()).isEqualTo(NOW);
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(token.getId()).isNotBlank();
        assertThat(token.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void issuesAdminTokenForAdminAudience() {
        Jwt token = tokens.decode(tokens.issue("admin", 5L, 6L, NOW.plusSeconds(3600)));

        assertThat(token.getClaimAsString("actorType")).isEqualTo("admin");
        assertThat(token.getAudience()).containsExactly("xinkelink-admin");
    }

    @Test
    void capsTokenExpirationAtSessionExpiration() {
        Jwt token = tokens.decode(tokens.issue("user", 1L, 2L, NOW.plusSeconds(80)));

        assertThat(token.getExpiresAt()).isEqualTo(NOW.plusSeconds(80));
    }

    @Test
    void givesEachIssuedTokenItsOwnIdentifier() {
        String first = tokens.issue("user", 1L, 2L, NOW.plusSeconds(3600));
        String second = tokens.issue("user", 1L, 2L, NOW.plusSeconds(3600));

        assertThat(tokens.decode(first).getId()).isNotEqualTo(tokens.decode(second).getId());
    }

    @Test
    void expiresAtExactExpirationWithoutClockSkew() {
        String token = tokens.issue("user", 1L, 2L, NOW.plusSeconds(20));
        JwtTokenService atExpiration = new JwtTokenService(new TokenProperties(secret, ISSUER),
                Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));

        rejects(atExpiration, token, "TOKEN_EXPIRED");
    }

    @Test
    void acceptsTokenUntilJustBeforeExpiration() {
        String token = tokens.issue("user", 1L, 2L, NOW.plusSeconds(20));
        JwtTokenService beforeExpiration = new JwtTokenService(new TokenProperties(secret, ISSUER),
                Clock.fixed(NOW.plusSeconds(20).minusNanos(1), ZoneOffset.UTC));

        assertThat(beforeExpiration.decode(token).getSubject()).isEqualTo("1");
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtTokenService otherSigner = new JwtTokenService(new TokenProperties(
                Base64.getEncoder().encodeToString(randomKey(32)), ISSUER), clock);

        rejects(tokens, otherSigner.issue("user", 1L, 2L, NOW.plusSeconds(3600)), "UNAUTHENTICATED");
    }

    @Test
    void rejectsWrongIssuer() {
        rejects(tokens, signed(claims -> claims.issuer("another-issuer")), "UNAUTHENTICATED");
    }

    @Test
    void rejectsUserTokenWithAdminAudience() {
        rejects(tokens, signed(claims -> claims.audience(List.of("xinkelink-admin"))), "UNAUTHENTICATED");
    }

    @Test
    void rejectsAdminTokenWithUserAudience() {
        rejects(tokens, signed(claims -> claims.claim("actorType", "admin")), "UNAUTHENTICATED");
    }

    @Test
    void rejectsAdditionalAudience() {
        rejects(tokens, signed(claims -> claims.audience(List.of("xinkelink-mini", "xinkelink-admin"))),
                "UNAUTHENTICATED");
    }

    @Test
    void rejectsUnknownActor() {
        rejects(tokens, signed(claims -> claims.claim("actorType", "operator")), "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "abc", "01", "9223372036854775808"})
    void rejectsInvalidSubject(String subject) {
        rejects(tokens, signed(claims -> claims.subject(subject)), "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "abc", "01", "9223372036854775808"})
    void rejectsInvalidSessionId(String sessionId) {
        rejects(tokens, signed(claims -> claims.claim("sid", sessionId)), "UNAUTHENTICATED");
    }

    @Test
    void rejectsFutureIssuedAt() {
        rejects(tokens, signed(claims -> claims.issuedAt(NOW.plusSeconds(1))), "UNAUTHENTICATED");
    }

    @Test
    void rejectsMissingIssuedAt() {
        rejects(tokens, signed(claims -> claims.claims(values -> values.remove("iat"))), "UNAUTHENTICATED");
    }

    @Test
    void rejectsMissingExpiration() {
        rejects(tokens, signed(claims -> claims.claims(values -> values.remove("exp"))), "UNAUTHENTICATED");
    }

    @Test
    void rejectsLifetimeLongerThanFifteenMinutes() {
        rejects(tokens, signed(claims -> claims.expiresAt(NOW.plusSeconds(901))), "UNAUTHENTICATED");
    }

    @Test
    void rejectsExpirationBeforeIssuedAt() {
        rejects(tokens, signed(claims -> claims.issuedAt(NOW.minusSeconds(60)).expiresAt(NOW.minusSeconds(61))),
                "UNAUTHENTICATED");
    }

    @Test
    void rejectsMissingIdentifier() {
        rejects(tokens, signed(claims -> claims.claims(values -> values.remove("jti"))), "UNAUTHENTICATED");
    }

    @Test
    void rejectsUnexpectedClaims() {
        rejects(tokens, signed(claims -> claims.claim("role", "ADMIN")), "UNAUTHENTICATED");
    }

    @Test
    void rejectsMalformedTokenWithStructuredError() {
        rejects(tokens, "malformed.token", "UNAUTHENTICATED");
    }

    @Test
    void rejectsShortSecretWithoutDisclosingIt() {
        String shortSecret = Base64.getEncoder().encodeToString(randomKey(16));

        assertThatThrownBy(() -> new JwtTokenService(new TokenProperties(shortSecret, ISSUER), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortSecret)
                .hasNoCause();
    }

    @Test
    void rejectsInvalidBase64WithoutDisclosingIt() {
        String invalidSecret = "invalid#jwt-secret";

        assertThatThrownBy(() -> new JwtTokenService(new TokenProperties(invalidSecret, ISSUER), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(invalidSecret)
                .hasNoCause();
    }

    @Test
    void rejectsAbsentSecretAndIssuer() {
        assertThatThrownBy(() -> new JwtTokenService(new TokenProperties(null, ISSUER), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtTokenService(new TokenProperties(secret, " "), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hidesSecretInConfigurationDescription() {
        assertThat(new TokenProperties(secret, ISSUER).toString()).doesNotContain(secret);
    }

    @Test
    void rejectsIssuanceForExpiredSessionOrInvalidIdentity() {
        assertThatThrownBy(() -> tokens.issue("user", 1L, 2L, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue("user", 0L, 2L, NOW.plusSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue("user", 1L, 0L, NOW.plusSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue("operator", 1L, 2L, NOW.plusSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String signed(Consumer<JwtClaimsSet.Builder> change) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(ISSUER).subject("12")
                .claim("sid", "34").claim("actorType", "user").audience(List.of("xinkelink-mini"))
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(900)).id(UUID.randomUUID().toString());
        change.accept(claims);
        // 绕过生产签发器构造恶意载荷，避免签发器提前拦截待验证场景。
        Map<String, Object> values = new LinkedHashMap<>(claims.build().getClaims());
        values.replaceAll((name, value) -> value instanceof Instant instant ? instant.getEpochSecond() : value);
        try {
            SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .type(JOSEObjectType.JWT).build(), JWTClaimsSet.parse(values));
            token.sign(new MACSigner(keyBytes));
            return token.serialize();
        } catch (Exception exception) {
            throw new AssertionError("测试令牌构造失败", exception);
        }
    }

    private void rejects(JwtTokenService service, String token, String code) {
        assertThatThrownBy(() -> service.decode(token)).isInstanceOfSatisfying(
                JwtValidationException.class, exception -> assertThat(exception.getErrors())
                        .extracting(OAuth2Error::getErrorCode).containsExactly(code));
    }

    private static byte[] randomKey(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
