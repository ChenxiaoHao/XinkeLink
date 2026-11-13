package cn.xinkelink.auth.ratelimit;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class RedisAuthRateLimiter implements AuthRateLimiter {
    // 计数和首次设置过期时间必须在同一个原子脚本中，后续请求不延长窗口。
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisAuthRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void check(String operation, String key) {
        if (!StringUtils.hasText(operation) || !StringUtils.hasText(key)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int limit = switch (operation) {
            case "wechat" -> 10;
            case "admin" -> 5;
            case "refresh" -> 30;
            case "beta" -> 5;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
        String redisKey = "xinkelink:auth:rate:" + operation + ":" + digest(key);
        Long count;
        try {
            count = redis.execute(COUNT, List.of(redisKey), "60");
        } catch (RuntimeException ignored) {
            // Redis 故障不能放行；原始异常可能包含连接凭据，不保留异常链。
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (count == null || count <= 0) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (count > limit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
    }

    private static String digest(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ignored) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256");
        }
    }
}
