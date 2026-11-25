package cn.xinkelink.forum.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostMetricsInvalidationTest {
    @Test void offlineRedisSubscriptionMustNotPreventApplicationStartup() throws Exception {
        var connections = mock(RedisConnectionFactory.class);
        when(connections.getConnection()).thenThrow(new RedisConnectionFailureException("offline test"));
        var cache = mock(PostMetricsCache.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("auth");
            context.registerBean(RedisConnectionFactory.class, () -> connections);
            context.registerBean(PostMetricsCache.class, () -> cache);
            context.register(PostMetricsInvalidation.class);
            assertDoesNotThrow(context::refresh);
            verify(cache, timeout(3000)).subscriptionFailed();
        }
    }
}
