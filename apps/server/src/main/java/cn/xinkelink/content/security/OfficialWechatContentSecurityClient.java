package cn.xinkelink.content.security;

import cn.xinkelink.auth.wechat.WechatProperties;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信官方内容安全客户端：使用 stable_token + wxa/msg_sec_check (version=2)。
 * 严格遵循不记录 appSecret / access_token、不跟随重定向、有界超时原则。
 */
public final class OfficialWechatContentSecurityClient implements WechatContentSecurityClient {
    private static final Logger log = LoggerFactory.getLogger(OfficialWechatContentSecurityClient.class);
    private static final String TOKEN_ENDPOINT = "https://api.weixin.qq.com/cgi-bin/stable_token";
    private static final String CHECK_ENDPOINT = "https://api.weixin.qq.com/wxa/msg_sec_check";
    private static final String MEDIA_CHECK_ENDPOINT = "https://api.weixin.qq.com/wxa/media_check_async";
    private static final Duration MIN_TOKEN_TTL = Duration.ofSeconds(60);

    private final WechatProperties wechat;
    private final ContentSecurityProperties properties;
    private final RestClient restClient;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public OfficialWechatContentSecurityClient(
            WechatProperties wechat,
            ContentSecurityProperties properties,
            RestClient restClient,
            StringRedisTemplate redis,
            JsonMapper json) {
        this.wechat = wechat;
        this.properties = properties;
        this.restClient = restClient;
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void checkText(String openId, ContentScene scene, String content) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(openId)) {
            return;
        }
        if (!StringUtils.hasText(wechat.appId()) || !StringUtils.hasText(wechat.appSecret())) {
            handleUpstreamFailure("wechat credentials not configured");
            return;
        }
        String token = fetchToken(false);
        if (!StringUtils.hasText(token)) {
            handleUpstreamFailure("failed to acquire wechat access_token");
            return;
        }
        JsonNode response = callCheck(token, openId, scene, content);
        if (response == null) {
            return;
        }
        int errcode = response.path("errcode").asInt(0);
        if (errcode == 40001 || errcode == 42001) {
            // token 失效，强制刷新一次重试
            String refreshed = fetchToken(true);
            if (!StringUtils.hasText(refreshed)) {
                handleUpstreamFailure("failed to refresh wechat access_token");
                return;
            }
            response = callCheck(refreshed, openId, scene, content);
            if (response == null) {
                return;
            }
            errcode = response.path("errcode").asInt(0);
        }
        if (errcode != 0) {
            handleUpstreamFailure("msg_sec_check returned errcode=" + errcode);
            return;
        }
        String suggest = response.path("result").path("suggest").asString("");
        if (!"pass".equalsIgnoreCase(suggest)) {
            throw new BusinessException(ErrorCode.CONTENT_REJECTED);
        }
    }

    @Override
    public java.util.Optional<String> checkImageAsync(String openId, ContentScene scene, String mediaUrl) {
        if (!StringUtils.hasText(mediaUrl) || !StringUtils.hasText(openId)) {
            return java.util.Optional.empty();
        }
        if (!StringUtils.hasText(wechat.appId()) || !StringUtils.hasText(wechat.appSecret())) {
            log.warn("WeChat media_check_async skipped: wechat credentials not configured");
            return java.util.Optional.empty();
        }
        String token = fetchToken(false);
        if (!StringUtils.hasText(token)) {
            log.warn("WeChat media_check_async failed to acquire access_token");
            return java.util.Optional.empty();
        }
        JsonNode response = callMediaCheck(token, openId, scene, mediaUrl);
        if (response == null) {
            return java.util.Optional.empty();
        }
        int errcode = response.path("errcode").asInt(0);
        if (errcode == 40001 || errcode == 42001) {
            String refreshed = fetchToken(true);
            if (!StringUtils.hasText(refreshed)) {
                return java.util.Optional.empty();
            }
            response = callMediaCheck(refreshed, openId, scene, mediaUrl);
            if (response == null) {
                return java.util.Optional.empty();
            }
            errcode = response.path("errcode").asInt(0);
        }
        if (errcode != 0) {
            log.warn("WeChat media_check_async returned errcode={}", errcode);
            return java.util.Optional.empty();
        }
        String traceId = response.path("trace_id").asString("");
        return StringUtils.hasText(traceId) ? java.util.Optional.of(traceId) : java.util.Optional.empty();
    }

    private JsonNode callMediaCheck(String accessToken, String openId, ContentScene scene, String mediaUrl) {
        URI uri = UriComponentsBuilder.fromUriString(MEDIA_CHECK_ENDPOINT)
                .queryParam("access_token", "{token}")
                .encode()
                .buildAndExpand(accessToken)
                .toUri();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("media_url", mediaUrl);
        payload.put("media_type", 2);
        payload.put("version", 2);
        payload.put("scene", scene.code());
        payload.put("openid", openId);
        try {
            String body = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(body)) {
                return null;
            }
            JsonNode node = json.readTree(body);
            return node.isObject() ? node : null;
        } catch (RestClientException | JacksonException ex) {
            log.warn("WeChat media_check_async network/parse error");
            return null;
        }
    }

    private JsonNode callCheck(String accessToken, String openId, ContentScene scene, String content) {
        URI uri = UriComponentsBuilder.fromUriString(CHECK_ENDPOINT)
                .queryParam("access_token", "{token}")
                .encode()
                .buildAndExpand(accessToken)
                .toUri();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("version", 2);
        payload.put("scene", scene.code());
        payload.put("openid", openId);
        try {
            String body = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(body)) {
                handleUpstreamFailure("empty msg_sec_check response");
                return null;
            }
            JsonNode node = json.readTree(body);
            if (!node.isObject()) {
                handleUpstreamFailure("invalid msg_sec_check response");
                return null;
            }
            return node;
        } catch (RestClientException | JacksonException ex) {
            handleUpstreamFailure("msg_sec_check network/parse error");
            return null;
        }
    }

    private String fetchToken(boolean forceRefresh) {
        String cacheKey = "xinkelink:wechat:stable_token:" + wechat.appId();
        if (!forceRefresh && redis != null) {
            try {
                String cached = redis.opsForValue().get(cacheKey);
                if (StringUtils.hasText(cached)) {
                    return cached;
                }
            } catch (RuntimeException ex) {
                log.warn("Redis read failed for wechat stable_token cache; falling back to direct fetch");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grant_type", "client_credential");
        payload.put("appid", wechat.appId());
        payload.put("secret", wechat.appSecret());
        payload.put("force_refresh", forceRefresh);
        try {
            String body = restClient.post()
                    .uri(URI.create(TOKEN_ENDPOINT))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(body)) {
                return null;
            }
            JsonNode node = json.readTree(body);
            String token = node.path("access_token").asString("");
            int expiresIn = node.path("expires_in").asInt(0);
            if (!StringUtils.hasText(token)) {
                return null;
            }
            if (redis != null) {
                long ttlSeconds = Math.max(MIN_TOKEN_TTL.toSeconds(), expiresIn - 300L);
                try {
                    redis.opsForValue().set(cacheKey, token, Duration.ofSeconds(ttlSeconds));
                } catch (RuntimeException ex) {
                    log.warn("Redis write failed for wechat stable_token cache");
                }
            }
            return token;
        } catch (RestClientException | JacksonException ex) {
            return null;
        }
    }

    private void handleUpstreamFailure(String reason) {
        log.warn("WeChat content security check unavailable: {}", reason);
        if (!properties.failOpen()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
