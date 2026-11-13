package cn.xinkelink.auth.wechat;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

public final class OfficialWechatCodeClient implements WechatCodeClient {
    private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private final WechatProperties properties;
    private final RestClient restClient;

    // 连接和读取超时由专用 RestClient 配置；适配器不重试一次性 code。
    public OfficialWechatCodeClient(WechatProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public WechatIdentity exchange(String code) {
        if (!StringUtils.hasText(code)) throw loginFailed();
        if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret()))
            throw new BusinessException(ErrorCode.WECHAT_NOT_CONFIGURED);

        // 使用固定绝对地址和严格编码的变量，不允许输入改变目标或增加查询参数。
        URI uri = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("appid", "{appId}")
                .queryParam("secret", "{appSecret}")
                .queryParam("js_code", "{code}")
                .queryParam("grant_type", "authorization_code")
                .encode()
                .buildAndExpand(properties.appId(), properties.appSecret(), code)
                .toUri();
        try {
            String body = restClient.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw loginFailed();
                    })
                    .body(String.class);
            if (!StringUtils.hasText(body)) {
                throw loginFailed();
            }

            JsonNode response = JSON.readTree(body);
            if (!response.isObject()) {
                throw loginFailed();
            }
            JsonNode errorCode = response.get("errcode");
            if (errorCode != null && (!errorCode.isIntegralNumber() || !errorCode.canConvertToInt()
                    || errorCode.intValue() != 0)) {
                throw loginFailed();
            }
            JsonNode openId = response.get("openid");
            if (openId == null || !openId.isString() || !StringUtils.hasText(openId.stringValue())) {
                throw loginFailed();
            }
            // 不保存、返回或记录 session_key，身份永远来自微信响应。
            return new WechatIdentity(properties.appId(), openId.stringValue());
        } catch (RestClientException | JacksonException ignored) {
            // 上游异常消息或请求地址可能包含密钥，不能保留为业务异常的 cause。
            throw loginFailed();
        }
    }

    private static BusinessException loginFailed() {
        return new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED);
    }
}
