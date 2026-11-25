package cn.xinkelink.content.security;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * 统一内容安全服务：
 * 1) 实时读取后台 xl_system_setting(content_review) 的敏感词配置并拦截；
 * 2) 开启微信内容安全开关时，调用微信 msg_sec_check v2 进行文本检测。
 */
@Service
@Profile("auth")
public class ContentSecurityService {
    private static final long SETTINGS_CACHE_MILLIS = 30_000L;
    private static final List<String> DEFAULT_SENSITIVE_WORDS = List.of(
            "代写论文", "期末代考", "英语四六级答案", "实验报告定制", "兼职加微信", "刷课插件", "高薪日结打工"
    );

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ContentSecurityProperties properties;
    private final WechatContentSecurityClient wechatClient;
    private final Clock clock;

    private volatile CachedReviewPolicy cachedPolicy;

    public ContentSecurityService(
            JdbcTemplate jdbc,
            JsonMapper json,
            ContentSecurityProperties properties,
            WechatContentSecurityClient wechatClient,
            Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.wechatClient = wechatClient;
        this.clock = clock;
    }

    public void checkText(long userId, ContentScene scene, String... texts) {
        if (texts == null || texts.length == 0) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (String text : texts) {
            if (StringUtils.hasText(text)) {
                if (!joined.isEmpty()) {
                    joined.append('\n');
                }
                joined.append(text.strip());
            }
        }
        if (joined.isEmpty()) {
            return;
        }
        String combined = joined.toString();

        // 1. 后台敏感词实时拦截
        ReviewPolicy policy = currentPolicy();
        if (policy.sensitiveWordIntercept() && !policy.sensitiveWords().isEmpty()) {
            String normalized = normalize(combined);
            for (String word : policy.sensitiveWords()) {
                String normalizedWord = normalize(word);
                if (!normalizedWord.isEmpty() && normalized.contains(normalizedWord)) {
                    throw new BusinessException(ErrorCode.CONTENT_REJECTED);
                }
            }
        }

        // 2. 微信官方 msg_sec_check 文本安全检测
        if (properties.wechatEnabled() && wechatClient != null) {
            String openId = findOpenId(userId);
            if (StringUtils.hasText(openId)) {
                wechatClient.checkText(openId, scene, combined);
            }
        }
    }

    public boolean isWechatEnabled() {
        return properties.wechatEnabled();
    }

    public void invalidateSettingsCache() {
        this.cachedPolicy = null;
    }

    private ReviewPolicy currentPolicy() {
        long now = clock.millis();
        CachedReviewPolicy current = this.cachedPolicy;
        if (current != null && now < current.expiresAtMillis()) {
            return current.policy();
        }
        ReviewPolicy loaded = loadPolicy();
        this.cachedPolicy = new CachedReviewPolicy(loaded, now + SETTINGS_CACHE_MILLIS);
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private ReviewPolicy loadPolicy() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT value_json FROM xl_system_setting WHERE setting_key='content_review'");
        if (rows.isEmpty()) {
            return new ReviewPolicy(true, DEFAULT_SENSITIVE_WORDS);
        }
        Object raw = rows.getFirst().get("value_json");
        Map<String, Object> decoded = raw instanceof byte[] bytes
                ? json.readValue(bytes, Map.class)
                : json.readValue(String.valueOf(raw), Map.class);
        boolean intercept = !Boolean.FALSE.equals(decoded.get("sensitiveWordIntercept"));
        List<String> words = new ArrayList<>();
        Object list = decoded.get("sensitiveWords");
        if (list instanceof Collection<?> items) {
            for (Object item : items) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    words.add(String.valueOf(item).strip());
                }
            }
        }
        return new ReviewPolicy(intercept, List.copyOf(words));
    }

    private String findOpenId(long userId) {
        List<String> rows = jdbc.query(
                "SELECT wechat_open_id FROM xl_user WHERE id = ?",
                (rs, rowNum) -> rs.getString(1),
                userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        // 去除零宽字符并统一小写，防止简单插字符绕过
        return input.replaceAll("[\\u200B-\\u200D\\uFEFF]", "").toLowerCase(Locale.ROOT);
    }

    private record ReviewPolicy(boolean sensitiveWordIntercept, List<String> sensitiveWords) {}
    private record CachedReviewPolicy(ReviewPolicy policy, long expiresAtMillis) {}
}
