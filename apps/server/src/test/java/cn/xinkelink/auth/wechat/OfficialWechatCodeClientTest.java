package cn.xinkelink.auth.wechat;

import cn.xinkelink.common.api.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OfficialWechatCodeClientTest {
    private MockRestServiceServer server;
    private OfficialWechatCodeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://untrusted.example");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OfficialWechatCodeClient(new WechatProperties("server-app-id", "server-secret+&"),
                builder.build());
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void exchangesCodeAtTheFixedOfficialEndpointWithEncodedServerCredentials() {
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                        + "?appid=server-app-id&secret=server-secret%2B%26"
                        + "&js_code=temporary%2Bcode%26%2F%3F%23%3D%E4%B8%AD%E6%96%87"
                        + "&grant_type=authorization_code"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"openid":"official-open-id","session_key":"private-session-key",
                         "unionid":"ignored-union-id"}
                        """, MediaType.APPLICATION_JSON));

        WechatIdentity identity = client.exchange("temporary+code&/?#=中文");

        assertEquals(new WechatIdentity("server-app-id", "official-open-id"), identity);
        assertFalse(identity.toString().contains("private-session-key"));
    }

    @Test
    void acceptsExplicitZeroErrorCodeAndIgnoresUnneededResponseFields() {
        expectExchange().andRespond(withSuccess("""
                {"openid":"official-open-id","session_key":"private-session-key",
                 "unionid":"ignored-union-id","errcode":0,"errmsg":"ok","future_field":true}
                """, MediaType.TEXT_PLAIN));

        assertEquals(new WechatIdentity("server-app-id", "official-open-id"), client.exchange("temporary-code"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 40029, 40226, 45011, 987654})
    void rejectsWechatBusinessErrorsEvenWhenAnOpenIdIsPresent(int errorCode) {
        expectExchange().andRespond(withSuccess("{\"openid\":\"untrusted-id\",\"errcode\":" + errorCode
                + ",\"errmsg\":\"server-secret+& private-session-key temporary-code\"}",
                MediaType.APPLICATION_JSON));

        assertSafeFailure(() -> client.exchange("temporary-code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]", "{}", "{\"openid\":null}",
            "{\"openid\":123}", "{\"openid\":\"\"}", "{\"openid\":\"  \"}",
            "{\"openid\":\"id\",\"errcode\":null}", "{\"openid\":\"id\",\"errcode\":\"0\"}",
            "{\"openid\":\"id\",\"errcode\":false}", "{\"openid\":\"id\",\"errcode\":0.5}",
            "{\"openid\":\"id\",\"errcode\":4294967296}",
            "{\"openid\":\"id\"} {\"errcode\":40029}",
            "{\"openid\":\"first\",\"openid\":\"second\"}"})
    void rejectsMalformedOrIncompleteWechatResponses(String response) {
        expectExchange().andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertSafeFailure(() -> client.exchange("temporary-code"));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 400, 401, 429, 500, 503})
    void rejectsEveryNonSuccessfulHttpStatusWithoutRevealingTheResponse(int status) {
        expectExchange().andRespond(withStatus(HttpStatus.valueOf(status))
                .body("server-secret+& private-session-key temporary-code")
                .contentType(MediaType.APPLICATION_JSON));

        assertSafeFailure(() -> client.exchange("temporary-code"));
    }

    @Test
    void mapsTransportTimeoutWithoutRetryingTheSingleUseCode() {
        expectExchange().andRespond(withException(
                new SocketTimeoutException("server-secret+& private-session-key temporary-code")));

        assertSafeFailure(() -> client.exchange("temporary-code"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsMissingCodeWithoutSendingARequest(String code) {
        assertSafeFailure(() -> client.exchange(code));
    }

    @Test
    void rejectsMissingServerCredentialsWithoutSendingARequest() {
        RestClient noNetworkClient = RestClient.builder().requestFactory((uri, method) -> {
                    fail("未配置凭据时不应向微信发出请求");
                    return null;
                }).build();
        OfficialWechatCodeClient missingAppId = new OfficialWechatCodeClient(new WechatProperties("", "secret"),
                noNetworkClient);
        OfficialWechatCodeClient missingSecret = new OfficialWechatCodeClient(new WechatProperties("app-id", " "),
                noNetworkClient);

        assertEquals("WECHAT_NOT_CONFIGURED",assertThrows(BusinessException.class,()->missingAppId.exchange("temporary-code")).code().name());
        assertEquals("WECHAT_NOT_CONFIGURED",assertThrows(BusinessException.class,()->missingSecret.exchange("temporary-code")).code().name());
    }

    @Test
    void propertyDiagnosticsDoNotExposeTheAppSecret() {
        assertFalse(new WechatProperties("server-app-id", "private-app-secret").toString()
                .contains("private-app-secret"));
    }

    private org.springframework.test.web.client.ResponseActions expectExchange() {
        return server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                + "?appid=server-app-id&secret=server-secret%2B%26"
                + "&js_code=temporary-code&grant_type=authorization_code"))
                .andExpect(method(HttpMethod.GET));
    }

    private static void assertSafeFailure(org.junit.jupiter.api.function.Executable exchange) {
        BusinessException failure = assertThrows(BusinessException.class, exchange);
        assertEquals("WECHAT_LOGIN_FAILED", failure.code().name());
        assertEquals(failure.code().message(), failure.getMessage());
        // 上游异常可能含完整请求地址和凭据，异常链也不能带出第三方原文。
        assertNull(failure.getCause());
    }
}
