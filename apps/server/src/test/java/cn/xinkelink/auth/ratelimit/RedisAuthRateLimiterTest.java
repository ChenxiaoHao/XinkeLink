package cn.xinkelink.auth.ratelimit;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisAuthRateLimiterTest {
    @Mock StringRedisTemplate redis;
    private RedisAuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RedisAuthRateLimiter(redis);
    }

    @ParameterizedTest
    @CsvSource({"wechat,10", "admin,5", "refresh,30"})
    void allowsTheLastRequestWithinEachOperationLimit(String operation, long limit) {
        when(redis.execute(RedisAuthRateLimiterTest.<RedisScript<Long>>script(), anyList(), eq("60")))
                .thenReturn(limit);

        assertDoesNotThrow(() -> limiter.check(operation, "abc"));
    }

    @ParameterizedTest
    @CsvSource({"wechat,11", "admin,6", "refresh,31"})
    void rejectsTheFirstRequestBeyondEachOperationLimit(String operation, long count) {
        when(redis.execute(RedisAuthRateLimiterTest.<RedisScript<Long>>script(), anyList(), eq("60")))
                .thenReturn(count);

        assertSafeFailure(ErrorCode.RATE_LIMITED, () -> limiter.check(operation, "abc"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void usesOneScriptInvocationWithOneDigestKeyAndASixtySecondWindow() {
        when(redis.execute(RedisAuthRateLimiterTest.<RedisScript<Long>>script(), anyList(), eq("60")))
                .thenReturn(1L);
        ArgumentCaptor<RedisScript<Long>> script = (ArgumentCaptor) ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keys = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);

        limiter.check("wechat", "abc");

        verify(redis).execute(script.capture(), keys.capture(), eq("60"));
        assertEquals(Long.class, script.getValue().getResultType());
        // 采用公开 SHA-256 测试向量，期望值不调用被测代码生成。
        assertEquals(List.of("xinkelink:auth:rate:wechat:"
                + "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), keys.getValue());
        verifyNoMoreInteractions(redis);
    }

    @Test
    void keepsOperationWindowsSeparateForTheSameIdentity() {
        when(redis.execute(RedisAuthRateLimiterTest.<RedisScript<Long>>script(), anyList(), eq("60")))
                .thenReturn(1L);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<String>> keys = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);

        limiter.check("wechat", "abc");
        limiter.check("admin", "abc");
        limiter.check("refresh", "abc");

        verify(redis, times(3)).execute(RedisAuthRateLimiterTest.<RedisScript<Long>>script(), keys.capture(), eq("60"));
        assertEquals(3, keys.getAllValues().stream().distinct().count());
    }

    @Test
    void failsClosedWhenRedisReturnsNoCounter() {
        assertSafeFailure(ErrorCode.SERVICE_UNAVAILABLE, () -> limiter.check("wechat", "abc"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void failsClosedWhenRedisReturnsAnInvalidCounter(long count) {
        when(redis.execute(RedisAuthRateLimiterTest.<RedisScript<Long>>script(), anyList(), eq("60")))
                .thenReturn(count);

        assertSafeFailure(ErrorCode.SERVICE_UNAVAILABLE, () -> limiter.check("wechat", "abc"));
    }

    @Test
    void failsClosedOnRedisConnectionFailureWithoutLeakingTheCause() {
        when(redis.execute(RedisAuthRateLimiterTest.<RedisScript<Long>>script(), anyList(), eq("60")))
                .thenThrow(new RedisConnectionFailureException("private-account redis-password"));

        assertSafeFailure(ErrorCode.SERVICE_UNAVAILABLE, () -> limiter.check("admin", "abc"));
    }

    @Test
    void failsClosedOnUnexpectedRedisExecutionFailureWithoutLeakingTheCause() {
        when(redis.execute(RedisAuthRateLimiterTest.<RedisScript<Long>>script(), anyList(), eq("60")))
                .thenThrow(new IllegalStateException("private-account redis-password"));

        assertSafeFailure(ErrorCode.SERVICE_UNAVAILABLE, () -> limiter.check("refresh", "abc"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "login", "WECHAT"})
    void rejectsUnknownOperationsBeforeCallingRedis(String operation) {
        assertSafeFailure(ErrorCode.VALIDATION_FAILED, () -> limiter.check(operation, "abc"));
        verifyNoInteractions(redis);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingIdentityBeforeCallingRedis(String key) {
        assertSafeFailure(ErrorCode.VALIDATION_FAILED, () -> limiter.check("wechat", key));
        verifyNoInteractions(redis);
    }

    private static <T> T script() {
        return any();
    }

    private static void assertSafeFailure(ErrorCode code, org.junit.jupiter.api.function.Executable check) {
        BusinessException failure = assertThrows(BusinessException.class, check);
        assertEquals(code, failure.code());
        assertEquals(code.message(), failure.getMessage());
        assertNull(failure.getCause());
    }
}
