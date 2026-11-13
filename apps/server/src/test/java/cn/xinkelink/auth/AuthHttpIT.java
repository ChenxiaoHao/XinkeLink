package cn.xinkelink.auth;

import cn.xinkelink.auth.ratelimit.AuthRateLimiter;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.auth.wechat.WechatCodeClient;
import cn.xinkelink.auth.wechat.WechatIdentity;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "auth"})
@Import(AuthHttpIT.TestAdapters.class)
@Transactional
class AuthHttpIT {
    private static final String SECRET = randomSecret();
    private static final String ORIGIN = "https://admin.test";
    private static final String ISSUER = "xinkelink-test";
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> SECRET);
        registry.add("xinkelink.auth.jwt.issuer", () -> ISSUER);
        registry.add("xinkelink.auth.admin.allowed-origins", () -> ORIGIN);
    }

    @Test
    void exchangesOnlyCodeAndCreatesOneUserWithoutLeakingWechatIdentity() throws Exception {
        JsonNode first = userLogin("first");
        JsonNode second = userLogin("first");
        assertEquals(first.at("/me/id").stringValue(), second.at("/me/id").stringValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id='it-auth-app'", Integer.class));
        assertEquals(900, first.get("expiresIn").intValue());
        assertTrue(first.get("refreshExpiresIn").longValue() > 2_590_000);
        assertNull(first.at("/me/openid").stringValue(null));
        assertFalse(first.toString().contains("it-open-first"));
        assertFalse(first.toString().contains("session_key"));
        assertFalse(first.toString().contains("studentId"));
    }

    @Test
    void rejectsClientSuppliedOpenidAndMissingCode() throws Exception {
        mvc.perform(post("/api/v1/auth/wechat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"first\",\"openid\":\"untrusted\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/wechat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upstreamFailureDoesNotCreateAUserOrExposeTheCode() throws Exception {
        mvc.perform(post("/api/v1/auth/wechat").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"unavailable-code\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("WECHAT_LOGIN_FAILED"))
                .andExpect(content().string(not(containsString("unavailable-code"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id='it-auth-app'", Integer.class));
    }

    @Test
    void bearerTokenReadsOnlyItsOwnUserAndCannotAccessAdmin() throws Exception {
        JsonNode user = userLogin("first");
        String token = user.get("accessToken").stringValue();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.at("/me/id").stringValue()))
                .andExpect(jsonPath("$.data.selfReported").value(true))
                .andExpect(jsonPath("$.data.openid").doesNotExist());
        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedTokenCannotAccessEvenAPublicHealthEndpoint() throws Exception {
        mvc.perform(get("/api/v1/health").header("Authorization", "Bearer invalid.jwt.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void expiredTokenReturnsTheAgreedErrorCode() throws Exception {
        JsonNode user = userLogin("first");
        JwtTokenService old = new JwtTokenService(new TokenProperties(SECRET, ISSUER),
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-16)));
        var current = new JwtTokenService(new TokenProperties(SECRET, ISSUER), Clock.systemUTC())
                .decode(user.get("accessToken").stringValue());
        String expired = old.issue("user", Long.parseLong(current.getSubject()),
                Long.parseLong(current.getClaimAsString("sid")), Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void refreshRotatesTheTicketAndRejectsReplayWithoutExtendingTheSession() throws Exception {
        JsonNode user = userLogin("first");
        String old = user.get("refreshToken").stringValue();
        String userId = user.at("/me/id").stringValue();
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", java.sql.Timestamp.class, userId).toInstant();
        JsonNode refreshed = data(mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("refreshToken", old)))).andExpect(status().isOk()).andReturn());
        assertNotEquals(old, refreshed.get("refreshToken").stringValue());
        assertEquals(expires, jdbc.queryForObject("SELECT expires_at FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", java.sql.Timestamp.class, userId).toInstant());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("refreshToken", old))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
        assertEquals(32, jdbc.queryForObject("SELECT OCTET_LENGTH(refresh_hash) FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", Integer.class, userId));
    }

    @Test
    void logoutImmediatelyRevokesAccessAndRefresh() throws Exception {
        JsonNode user = userLogin("first");
        String access = user.get("accessToken").stringValue();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revoked").value(true));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("refreshToken", user.get("refreshToken").stringValue()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bansAndSoftDeletionAreCheckedAgainstTheCurrentDatabaseState() throws Exception {
        JsonNode user = userLogin("first");
        jdbc.update("UPDATE xl_user SET ban_state='permanent' WHERE id=?", user.at("/me/id").stringValue());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + user.get("accessToken").stringValue()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));
        mvc.perform(post("/api/v1/auth/wechat").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"first\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE xl_user SET ban_state='none',deleted_at=CURRENT_TIMESTAMP(3) WHERE id=?", user.at("/me/id").stringValue());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + user.get("accessToken").stringValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTemporaryBanDoesNotClearManualRestrictions() throws Exception {
        JsonNode user = userLogin("first");
        jdbc.update("UPDATE xl_user SET ban_state='temporary',ban_until=?,restrict_publish=1 WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), user.at("/me/id").stringValue());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + user.get("accessToken").stringValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("restricted"))
                .andExpect(jsonPath("$.data.restrictions.publish").value(true));
    }

    @Test
    void adminPasswordIsHashedAndTokenCannotBecomeAStudent() throws Exception {
        AdminFixture fixture = createAdmin();
        MvcResult login = adminLogin(fixture);
        JsonNode admin = data(login);
        assertFalse(admin.toString().contains("password"));
        assertFalse(admin.has("refreshToken"));
        String stored = jdbc.queryForObject("SELECT password_hash FROM xl_admin WHERE login_name=?", String.class, fixture.account());
        assertNotEquals(fixture.password(), stored);
        assertTrue(passwords.matches(fixture.password(), stored));
        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + admin.get("accessToken").stringValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + admin.get("accessToken").stringValue()))
                .andExpect(status().isForbidden());
        assertTrue(login.getResponse().getHeader("Set-Cookie").contains("HttpOnly"));
        assertTrue(login.getResponse().getHeader("Set-Cookie").contains("Secure"));
        assertTrue(login.getResponse().getHeader("Set-Cookie").contains("SameSite=Strict"));
    }

    @Test
    void wrongAndDisabledAdminCredentialsUseTheSameSafeFailure() throws Exception {
        AdminFixture fixture = createAdmin();
        mvc.perform(post("/api/v1/admin/auth/login").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("account", fixture.account(), "password", "wrong"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        jdbc.update("UPDATE xl_admin SET status='disabled' WHERE login_name=?", fixture.account());
        mvc.perform(post("/api/v1/admin/auth/login").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("account", fixture.account(), "password", fixture.password()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void adminRefreshRequiresTheCookieTrustedOriginAndBoundCsrf() throws Exception {
        MvcResult login = adminLogin(createAdmin());
        Cookie cookie = refreshCookie(login);
        String csrf = data(login).get("csrfToken").stringValue();
        mvc.perform(post("/api/v1/admin/auth/refresh").cookie(cookie).header("Origin", ORIGIN))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/auth/refresh").cookie(cookie).header("Origin", "https://evil.test").header("X-CSRF-Token", csrf))
                .andExpect(status().isForbidden());
        MvcResult refreshed = mvc.perform(post("/api/v1/admin/auth/refresh").cookie(cookie)
                        .header("Origin", ORIGIN).header("X-CSRF-Token", csrf))
                .andExpect(status().isOk()).andReturn();
        assertNotEquals(cookie.getValue(), refreshCookie(refreshed).getValue());
        mvc.perform(post("/api/v1/admin/auth/refresh").cookie(cookie).header("Origin", ORIGIN).header("X-CSRF-Token", csrf))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void adminLogoutClearsCookieAndRevokesAccess() throws Exception {
        MvcResult login = adminLogin(createAdmin());
        String token = data(login).get("accessToken").stringValue();
        mvc.perform(post("/api/v1/admin/auth/logout").cookie(refreshCookie(login)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", data(login).get("csrfToken").stringValue()))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidAccessOnAdminLogoutStillExpiresTheTrustedBrowserCookie() throws Exception {
        MvcResult login = adminLogin(createAdmin());
        mvc.perform(post("/api/v1/admin/auth/logout").cookie(refreshCookie(login)).header("Origin", ORIGIN)
                        .header("Authorization", "Bearer invalid.jwt.value"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mvc.perform(post("/api/v1/admin/auth/logout").cookie(refreshCookie(login)).header("Origin", "https://evil.test")
                        .header("Authorization", "Bearer invalid.jwt.value"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void userRefreshCannotBeUsedAsAnAdminRefreshCookie() throws Exception {
        JsonNode user = userLogin("first");
        mvc.perform(post("/api/v1/admin/auth/refresh")
                        .cookie(new Cookie("xinkelink_admin_refresh", user.get("refreshToken").stringValue()))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", "not-bound"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disablingALoggedInAdminRejectsBothExistingAccessAndRefresh() throws Exception {
        AdminFixture fixture = createAdmin();
        MvcResult login = adminLogin(fixture);
        jdbc.update("UPDATE xl_admin SET status='disabled' WHERE login_name=?", fixture.account());
        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + data(login).get("accessToken").stringValue()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/auth/refresh").cookie(refreshCookie(login)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", data(login).get("csrfToken").stringValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void databaseSessionExpiryRejectsAnOtherwiseValidAccessAndRefresh() throws Exception {
        JsonNode login = userLogin("first");
        jdbc.update("UPDATE xl_auth_session SET expires_at=? WHERE subject_type='user' AND subject_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(10)), login.at("/me/id").stringValue());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + login.get("accessToken").stringValue()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("refreshToken", login.get("refreshToken").stringValue()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void oldCsrfCannotConsumeTheNewRefreshTicket() throws Exception {
        MvcResult login = adminLogin(createAdmin());
        String oldCsrf = data(login).get("csrfToken").stringValue();
        MvcResult rotated = mvc.perform(post("/api/v1/admin/auth/refresh").cookie(refreshCookie(login))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", oldCsrf)).andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/v1/admin/auth/refresh").cookie(refreshCookie(rotated))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", oldCsrf))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/v1/admin/auth/refresh").cookie(refreshCookie(rotated))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", data(rotated).get("csrfToken").stringValue()))
                .andExpect(status().isOk());
    }

    private JsonNode userLogin(String code) throws Exception {
        return data(mvc.perform(post("/api/v1/auth/wechat").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("code", code)))).andExpect(status().isOk()).andReturn());
    }

    private MvcResult adminLogin(AdminFixture fixture) throws Exception {
        return mvc.perform(post("/api/v1/admin/auth/login").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("account", fixture.account(), "password", fixture.password()))))
                .andExpect(status().isOk()).andReturn();
    }

    private AdminFixture createAdmin() {
        String account = "it-" + UUID.randomUUID();
        String password = "test-only-" + UUID.randomUUID();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,?,?,CURRENT_TIMESTAMP(3))",
                account, passwords.encode(password), "认证回滚测试");
        return new AdminFixture(account, password);
    }

    private Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("xinkelink_admin_refresh");
        assertNotNull(cookie);
        return cookie;
    }

    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("data"); }
    private static String randomSecret() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
    private record AdminFixture(String account, String password) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAdapters {
        // 只替换外部微信及Redis边界；认证、签名、数据库与HTTP走真实实现。
        @Bean @Primary WechatCodeClient testWechat() {
            return code -> {
                if (code.equals("unavailable-code")) throw new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED);
                return new WechatIdentity("it-auth-app", "it-open-" + code);
            };
        }
        @Bean @Primary AuthRateLimiter testLimiter() { return (operation, key) -> {}; }
    }
}
