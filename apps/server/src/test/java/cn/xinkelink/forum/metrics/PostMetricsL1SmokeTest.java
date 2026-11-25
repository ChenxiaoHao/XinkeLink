package cn.xinkelink.forum.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class PostMetricsL1SmokeTest {
    @Test void hotL1MustAvoidSecondRedisRoundTrip() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyCollection())).thenReturn(Collections.singletonList(null));
        new MockPostMetricsRedis(redis, values, new HashMap<>(), java.time.Clock.systemUTC());
        var cache = new PostMetricsCache(redis,true,20,0,true,10000,2000,new SimpleMeterRegistry());
        var expected = Map.of(1L,new PostMetricsCache.Counts(2,3));
        assertEquals(expected,cache.get(List.of(1L), ids -> expected));
        assertEquals(expected,cache.get(List.of(1L), ids -> expected));
        verify(values,times(1)).multiGet(anyCollection());
    }
}
