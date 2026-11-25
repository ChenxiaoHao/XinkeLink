package cn.xinkelink.forum.metrics;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Best-effort public-count invalidation. Initial Redis failure must not prevent DB fallback. */
@Configuration(proxyBeanMethods = false)
@Profile("auth")
public class PostMetricsInvalidation {
    @Bean
    Subscription postMetricsInvalidationContainer(RedisConnectionFactory connections, PostMetricsCache cache) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.setRecoveryInterval(1000);
        container.setErrorHandler(error -> cache.subscriptionFailed());
        container.addMessageListener(new Listener(cache), new ChannelTopic(PostMetricsCache.CHANNEL));
        container.afterPropertiesSet();
        // Only the wrapper participates in Spring lifecycle; the delegate is not an auto-start bean.
        return new Subscription(container, cache);
    }

    static final class Subscription implements SmartLifecycle, DisposableBean {
        private final RedisMessageListenerContainer delegate;
        private final PostMetricsCache cache;
        private ScheduledExecutorService retry;
        Subscription(RedisMessageListenerContainer delegate, PostMetricsCache cache) {
            this.delegate = delegate; this.cache = cache;
        }
        @Override public synchronized void start() {
            if (retry != null) return;
            retry = Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "post-metrics-subscription"); thread.setDaemon(true); return thread;
            });
            retry.scheduleWithFixedDelay(this::subscribe, 0, 1000, TimeUnit.MILLISECONDS);
        }
        private synchronized void subscribe() {
            if (retry == null || delegate.isListening()) return;
            try { delegate.stop(); delegate.start(); }
            catch (RuntimeException ignored) { cache.subscriptionFailed(); }
        }
        @Override public synchronized void stop() {
            if (retry == null) return;
            retry.shutdownNow(); retry = null;
            delegate.stop(); cache.onSubscriptionRestored();
        }
        @Override public synchronized boolean isRunning() { return retry != null; }
        @Override public void destroy() throws Exception { stop(); delegate.destroy(); }
    }

    static final class Listener implements MessageListener, SubscriptionListener {
        private final PostMetricsCache cache;
        Listener(PostMetricsCache cache) { this.cache = cache; }
        @Override public void onMessage(Message message, byte[] pattern) {
            cache.receiveInvalidation(new String(message.getBody(), StandardCharsets.UTF_8));
        }
        @Override public void onChannelSubscribed(byte[] channel, long count) { cache.onSubscriptionRestored(); }
        @Override public void onChannelUnsubscribed(byte[] channel, long count) { cache.onSubscriptionRestored(); }
    }
}
