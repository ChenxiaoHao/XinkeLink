package cn.xinkelink.content.security;

import cn.xinkelink.auth.wechat.WechatProperties;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ContentSecurityServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void blocksConfiguredSensitiveWordAndZeroWidthBypass() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT value_json FROM xl_system_setting WHERE setting_key='content_review'"))
                .thenReturn(List.of(Map.of("value_json", "{\"sensitiveWordIntercept\":true,\"sensitiveWords\":[\"代写论文\",\"BadWord\"]}")));
        WechatContentSecurityClient wechat = mock(WechatContentSecurityClient.class);

        ContentSecurityService service = new ContentSecurityService(
                jdbc, JSON, new ContentSecurityProperties(false, false, false, "", ""), wechat, FIXED_CLOCK);

        service.checkText(1L, ContentScene.FORUM, "今天图书馆二楼有空位");

        BusinessException direct = assertThrows(BusinessException.class,
                () -> service.checkText(1L, ContentScene.FORUM, "承接代写论文联系我"));
        assertEquals(ErrorCode.CONTENT_REJECTED, direct.code());

        BusinessException zeroWidth = assertThrows(BusinessException.class,
                () -> service.checkText(1L, ContentScene.COMMENT, "代\u200B写\uFEFF论文"));
        assertEquals(ErrorCode.CONTENT_REJECTED, zeroWidth.code());

        BusinessException caseInsensitive = assertThrows(BusinessException.class,
                () -> service.checkText(1L, ContentScene.MESSAGE, "this has badword inside"));
        assertEquals(ErrorCode.CONTENT_REJECTED, caseInsensitive.code());

        verifyNoInteractions(wechat);
    }

    @Test
    void respectsDisabledSensitiveWordToggleAndCacheInvalidation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT value_json FROM xl_system_setting WHERE setting_key='content_review'"))
                .thenReturn(List.of(Map.of("value_json", "{\"sensitiveWordIntercept\":false,\"sensitiveWords\":[\"测试违禁词\"]}")))
                .thenReturn(List.of(Map.of("value_json", "{\"sensitiveWordIntercept\":true,\"sensitiveWords\":[\"测试违禁词\"]}")));

        ContentSecurityService service = new ContentSecurityService(
                jdbc, JSON, new ContentSecurityProperties(false, false, false, "", ""), null, FIXED_CLOCK);

        assertDoesNotThrow(() -> service.checkText(1L, ContentScene.FORUM, "包含测试违禁词"));

        service.invalidateSettingsCache();
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> service.checkText(1L, ContentScene.FORUM, "包含测试违禁词"));
        assertEquals(ErrorCode.CONTENT_REJECTED, blocked.code());
    }

    @Test
    void callsWechatClientWhenEnabledAndRejectsRiskyResult() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT value_json FROM xl_system_setting WHERE setting_key='content_review'"))
                .thenReturn(List.of());
        when(jdbc.query(eq("SELECT wechat_open_id FROM xl_user WHERE id = ?"), any(RowMapper.class), eq(42L)))
                .thenReturn(List.of("openid-42"));

        WechatContentSecurityClient wechat = mock(WechatContentSecurityClient.class);
        doThrow(new BusinessException(ErrorCode.CONTENT_REJECTED))
                .when(wechat).checkText("openid-42", ContentScene.PROFILE, "违规昵称");

        ContentSecurityService service = new ContentSecurityService(
                jdbc, JSON, new ContentSecurityProperties(true, false, false, "", ""), wechat, FIXED_CLOCK);

        service.checkText(42L, ContentScene.PROFILE, "正常昵称");
        verify(wechat).checkText("openid-42", ContentScene.PROFILE, "正常昵称");

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.checkText(42L, ContentScene.PROFILE, "违规昵称"));
        assertEquals(ErrorCode.CONTENT_REJECTED, rejected.code());
    }

    @Test
    void officialClientHandlesPassRiskyTokenRefreshAndFailClosed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OfficialWechatContentSecurityClient client = new OfficialWechatContentSecurityClient(
                new WechatProperties("wx-test", "secret-test"),
                new ContentSecurityProperties(true, false, false, "", ""),
                builder.build(),
                null,
                JSON);

        // 1. pass
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withSuccess("{\"access_token\":\"tok-1\",\"expires_in\":7200}", APPLICATION_JSON));
        server.expect(requestTo("https://api.weixin.qq.com/wxa/msg_sec_check?access_token=tok-1"))
                .andRespond(withSuccess("{\"errcode\":0,\"result\":{\"suggest\":\"pass\",\"label\":100}}", APPLICATION_JSON));
        assertDoesNotThrow(() -> client.checkText("openid-1", ContentScene.FORUM, "正常校园帖子"));
        server.verify();
        server.reset();

        // 2. expired token (40001) -> force refresh -> risky -> CONTENT_REJECTED
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withSuccess("{\"access_token\":\"tok-expired\",\"expires_in\":7200}", APPLICATION_JSON));
        server.expect(requestTo("https://api.weixin.qq.com/wxa/msg_sec_check?access_token=tok-expired"))
                .andRespond(withSuccess("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}", APPLICATION_JSON));
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withSuccess("{\"access_token\":\"tok-2\",\"expires_in\":7200}", APPLICATION_JSON));
        server.expect(requestTo("https://api.weixin.qq.com/wxa/msg_sec_check?access_token=tok-2"))
                .andRespond(withSuccess("{\"errcode\":0,\"result\":{\"suggest\":\"risky\",\"label\":20001}}", APPLICATION_JSON));

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> client.checkText("openid-1", ContentScene.FORUM, "敏感内容"));
        assertEquals(ErrorCode.CONTENT_REJECTED, rejected.code());
        server.verify();
        server.reset();

        // 3. upstream error with failOpen=false -> SERVICE_UNAVAILABLE
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withServerError());
        BusinessException unavailable = assertThrows(BusinessException.class,
                () -> client.checkText("openid-1", ContentScene.FORUM, "任意内容"));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, unavailable.code());

        server.verify();
    }
}
