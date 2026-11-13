package cn.xinkelink.auth;

import cn.xinkelink.auth.ratelimit.AuthRateLimiter;
import cn.xinkelink.auth.wechat.WechatCodeClient;
import cn.xinkelink.auth.wechat.WechatIdentity;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"db", "auth"})
@Import(AuthLiveHttpIT.TestAdapters.class)
class AuthLiveHttpIT {
    private static final String APP_ID = "itw-" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
    private static final AtomicBoolean UNAVAILABLE = new AtomicBoolean();
    @Value("${local.server.port}") int port;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("xinkelink.auth.jwt.secret-base64", () -> secret);
    }

    @Test
    void loginRefreshAuthorizationAndLogoutWorkAcrossARealHttpPort() throws Exception {
        String code = UUID.randomUUID().toString();
        try {
            JsonNode login = body(request("POST", "/api/v1/auth/wechat", Map.of("code", code), null), 200).get("data");
            String access = login.get("accessToken").stringValue();
            String refresh = login.get("refreshToken").stringValue();
            JsonNode me = body(request("GET", "/api/v1/me", null, access), 200).get("data");
            assertEquals(login.at("/me/id").stringValue(), me.get("id").stringValue());
            body(request("GET", "/api/v1/admin/me", null, access), 403);
            JsonNode rotated = body(request("POST", "/api/v1/auth/refresh", Map.of("refreshToken", refresh), null), 200).get("data");
            assertNotEquals(refresh, rotated.get("refreshToken").stringValue());
            assertEquals("REFRESH_INVALID", body(request("POST", "/api/v1/auth/refresh", Map.of("refreshToken", refresh), null), 401).get("code").stringValue());
            String current = rotated.get("accessToken").stringValue();
            body(request("POST", "/api/v1/auth/logout", null, current), 200);
            body(request("GET", "/api/v1/me", null, current), 401);
        } finally {
            // HTTP工作线程独立提交，结束后只清理本测试精确身份，不触碰已有用户。
            for (long id : jdbc.queryForList("SELECT id FROM xl_user WHERE wechat_app_id=? AND wechat_open_id=?", Long.class, APP_ID, code)) {
                jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", id);
                jdbc.update("DELETE FROM xl_user WHERE id=? AND wechat_app_id=? AND wechat_open_id=?", id, APP_ID, code);
            }
        }
    }

    @Test
    void unavailableRateLimitBoundaryFailsClosedBeforeIdentityCreation() throws Exception {
        UNAVAILABLE.set(true);
        try {
            JsonNode response = body(request("POST", "/api/v1/auth/wechat", Map.of("code", "blocked"), null), 503);
            assertEquals("SERVICE_UNAVAILABLE", response.get("code").stringValue());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=? AND wechat_open_id='blocked'", Integer.class, APP_ID));
        } finally { UNAVAILABLE.set(false); }
    }

    private HttpResponse<String> request(String method, String path, Map<String, String> payload, String access) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (access != null) request.header("Authorization", "Bearer " + access);
        request.header("Content-Type", "application/json");
        return client.send(request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode());
        JsonNode body = json.readTree(response.body());
        assertTrue(body.get("requestId").isString());
        return body;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAdapters {
        // 外部微信与限流使用显式测试替身；真实HTTP、JWT、权限和MySQL保持完整实现。
        @Bean @Primary WechatCodeClient testWechat() { return code -> new WechatIdentity(APP_ID, code); }
        @Bean @Primary AuthRateLimiter testLimiter() {
            return (operation, key) -> { if (UNAVAILABLE.get()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); };
        }
    }
}
