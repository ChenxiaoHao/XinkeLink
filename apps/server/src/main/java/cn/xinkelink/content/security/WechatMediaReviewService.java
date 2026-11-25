package cn.xinkelink.content.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微信图片异步内容安全审核服务（media_check_async v2 + 异步回调自动处置）。
 * - 上传图片后异步提交检测，将 trace_id -> imageId 存入 Redis（24 小时 TTL）；
 * - 收到微信回调且 suggest != pass 时，自动隐藏关联帖子（进入后台待复核队列）或清除违规头像。
 */
@Service
@Profile("auth")
public class WechatMediaReviewService {
    private static final Logger log = LoggerFactory.getLogger(WechatMediaReviewService.class);
    private static final String TRACE_KEY_PREFIX = "xinkelink:wechat:media_trace:";
    private static final Duration TRACE_TTL = Duration.ofHours(24);

    private final ContentSecurityProperties properties;
    private final WechatContentSecurityClient wechatClient;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public WechatMediaReviewService(
            ContentSecurityProperties properties,
            WechatContentSecurityClient wechatClient,
            ObjectProvider<StringRedisTemplate> redis,
            JdbcTemplate jdbc) {
        this.properties = properties;
        this.wechatClient = wechatClient;
        this.redis = redis.getIfAvailable();
        this.jdbc = jdbc;
    }

    public boolean isEnabled() {
        return properties.mediaCheckEnabled() && wechatClient != null && StringUtils.hasText(properties.publicBaseUrl());
    }

    public Optional<String> submitImageCheck(long userId, long imageId, String purpose, String relativeOrAbsoluteUrl) {
        if (!isEnabled() || !StringUtils.hasText(relativeOrAbsoluteUrl)) {
            return Optional.empty();
        }
        String openId = findOpenId(userId);
        if (!StringUtils.hasText(openId)) {
            return Optional.empty();
        }
        String mediaUrl = toPublicUrl(relativeOrAbsoluteUrl);
        if (!StringUtils.hasText(mediaUrl)) {
            return Optional.empty();
        }
        ContentScene scene = "avatar".equals(purpose) ? ContentScene.PROFILE : ContentScene.FORUM;
        Optional<String> traceId = wechatClient.checkImageAsync(openId, scene, mediaUrl);
        traceId.ifPresent(id -> rememberTrace(id, imageId));
        return traceId;
    }

    public boolean verifySignature(String signature, String timestamp, String nonce) {
        if (!StringUtils.hasText(properties.callbackToken())
                || !StringUtils.hasText(signature)
                || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(nonce)) {
            return false;
        }
        String[] parts = { properties.callbackToken(), timestamp, nonce };
        Arrays.sort(parts);
        String joined = parts[0] + parts[1] + parts[2];
        String expected = sha1Hex(joined);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public boolean handleCallback(String traceId, String suggest) {
        if (!StringUtils.hasText(traceId) || "pass".equalsIgnoreCase(suggest)) {
            return false;
        }
        Long imageId = resolveImageId(traceId);
        if (imageId == null) {
            log.warn("WeChat media_check callback trace_id not found or expired: {}", traceId);
            return false;
        }
        List<Map<String, Object>> refs = jdbc.queryForList(
                "SELECT owner_type, owner_id FROM xl_image_ref WHERE image_id = ?", imageId);
        int affected = 0;
        for (Map<String, Object> ref : refs) {
            String ownerType = String.valueOf(ref.get("owner_type"));
            long ownerId = ((Number) ref.get("owner_id")).longValue();
            if ("post".equals(ownerType)) {
                affected += jdbc.update("""
                        UPDATE xl_post
                        SET status = 'hidden', review_state = 'pending', updated_at = UTC_TIMESTAMP(3), version = version + 1
                        WHERE id = ? AND status = 'visible' AND deleted_at IS NULL
                        """, ownerId);
            } else if ("user_avatar".equals(ownerType)) {
                affected += jdbc.update("""
                        UPDATE xl_user
                        SET avatar_image_id = NULL, updated_at = UTC_TIMESTAMP(3), version = version + 1
                        WHERE id = ? AND avatar_image_id = ?
                        """, ownerId, imageId);
            }
        }
        log.warn("WeChat media_check rejected imageId={} suggest={} affectedRefs={}", imageId, suggest, affected);
        return affected > 0;
    }

    private String toPublicUrl(String url) {
        if (url.startsWith("https://")) {
            return url;
        }
        String base = properties.publicBaseUrl().strip();
        if (!base.startsWith("https://")) {
            return "";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return url.startsWith("/") ? base + url : base + "/" + url;
    }

    private String findOpenId(long userId) {
        List<String> rows = jdbc.query(
                "SELECT wechat_open_id FROM xl_user WHERE id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> rs.getString(1),
                userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void rememberTrace(String traceId, long imageId) {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(TRACE_KEY_PREFIX + traceId, Long.toString(imageId), TRACE_TTL);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist WeChat media_check trace_id in Redis");
        }
    }

    private Long resolveImageId(String traceId) {
        if (redis == null) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(TRACE_KEY_PREFIX + traceId);
            return StringUtils.hasText(value) ? Long.parseLong(value) : null;
        } catch (RuntimeException ex) {
            log.warn("Failed to read WeChat media_check trace_id from Redis");
            return null;
        }
    }

    private static String sha1Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
