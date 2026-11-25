package cn.xinkelink.content.security;

import cn.xinkelink.auth.wechat.WechatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WechatMediaReviewServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void verifiesWechatSha1Signature() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        ContentSecurityProperties props = new ContentSecurityProperties(true, false, true, "my-secret-token", "https://example.com");
        WechatMediaReviewService service = new WechatMediaReviewService(props, mock(WechatContentSecurityClient.class), redisProvider, mock(JdbcTemplate.class));

        String[] parts = { "my-secret-token", "1732530000", "nonce123" };
        Arrays.sort(parts);
        byte[] digest = MessageDigest.getInstance("SHA-1").digest((parts[0] + parts[1] + parts[2]).getBytes(StandardCharsets.UTF_8));
        StringBuilder sig = new StringBuilder();
        for (byte b : digest) sig.append(String.format("%02x", b));

        assertTrue(service.verifySignature(sig.toString(), "1732530000", "nonce123"));
        assertFalse(service.verifySignature("bad-signature", "1732530000", "nonce123"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitsAsyncCheckAndHidesPostOrAvatarOnRiskyCallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq("SELECT wechat_open_id FROM xl_user WHERE id = ? AND deleted_at IS NULL"), any(RowMapper.class), eq(7L)))
                .thenReturn(List.of("openid-7"));
        when(ops.get("xinkelink:wechat:media_trace:trace-999")).thenReturn("501");
        when(jdbc.queryForList("SELECT owner_type, owner_id FROM xl_image_ref WHERE image_id = ?", 501L))
                .thenReturn(List.of(
                        Map.of("owner_type", "post", "owner_id", 88L),
                        Map.of("owner_type", "user_avatar", "owner_id", 7L)));
        when(jdbc.update(contains("UPDATE xl_post"), eq(88L))).thenReturn(1);
        when(jdbc.update(contains("UPDATE xl_user"), eq(7L), eq(501L))).thenReturn(1);

        WechatContentSecurityClient client = mock(WechatContentSecurityClient.class);
        when(client.checkImageAsync("openid-7", ContentScene.FORUM, "https://example.com/api/v1/images/501/content?grant=abc"))
                .thenReturn(Optional.of("trace-999"));

        ContentSecurityProperties props = new ContentSecurityProperties(true, false, true, "tok", "https://example.com/");
        WechatMediaReviewService service = new WechatMediaReviewService(props, client, redisProvider, jdbc);

        Optional<String> trace = service.submitImageCheck(7L, 501L, "post", "/api/v1/images/501/content?grant=abc");
        assertEquals(Optional.of("trace-999"), trace);
        verify(ops).set("xinkelink:wechat:media_trace:trace-999", "501", Duration.ofHours(24));

        assertFalse(service.handleCallback("trace-999", "pass"));
        assertTrue(service.handleCallback("trace-999", "risky"));
        verify(jdbc).update(contains("UPDATE xl_post"), eq(88L));
        verify(jdbc).update(contains("UPDATE xl_user"), eq(7L), eq(501L));
    }

    @Test
    void officialClientSubmitsMediaCheckAsyncAndReturnsTraceId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OfficialWechatContentSecurityClient client = new OfficialWechatContentSecurityClient(
                new WechatProperties("wx-test", "secret-test"),
                new ContentSecurityProperties(true, false, true, "tok", "https://example.com"),
                builder.build(),
                null,
                JSON);

        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withSuccess("{\"access_token\":\"tok-media\",\"expires_in\":7200}", APPLICATION_JSON));
        server.expect(requestTo("https://api.weixin.qq.com/wxa/media_check_async?access_token=tok-media"))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\",\"trace_id\":\"wx-trace-123\"}", APPLICATION_JSON));

        Optional<String> traceId = client.checkImageAsync("openid-1", ContentScene.FORUM, "https://example.com/a.jpg");
        assertEquals(Optional.of("wx-trace-123"), traceId);
        server.verify();
    }
}
