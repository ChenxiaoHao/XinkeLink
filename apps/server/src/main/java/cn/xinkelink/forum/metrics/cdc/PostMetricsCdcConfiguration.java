package cn.xinkelink.forum.metrics.cdc;

import cn.xinkelink.forum.metrics.PostMetricsCache;
import com.alibaba.otter.canal.client.impl.SimpleCanalConnector;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;

@Configuration(proxyBeanMethods=false)
@Profile("auth")
public class PostMetricsCdcConfiguration {
    @Bean
    PostMetricsCdcConsumer postMetricsCdcConsumer(PostMetricsCache cache, MeterRegistry meters,
            @Value("${xinkelink.cache.post-metrics.cdc.enabled:false}") boolean enabled,
            @Value("${xinkelink.cache.post-metrics.cdc.host:127.0.0.1}") String host,
            @Value("${xinkelink.cache.post-metrics.cdc.port:11111}") int port,
            @Value("${xinkelink.cache.post-metrics.cdc.destination:post_metrics}") String destination,
            @Value("${xinkelink.cache.post-metrics.cdc.username:}") String username,
            @Value("${xinkelink.cache.post-metrics.cdc.password:}") String password,
            @Value("${xinkelink.cache.post-metrics.cdc.schema:}") String schema,
            @Value("${xinkelink.cache.post-metrics.cdc.checkpoint:}") String checkpoint,
            @Value("${xinkelink.cache.post-metrics.cdc.batch-size:512}") int batchSize,
            @Value("${xinkelink.cache.post-metrics.cdc.poll-millis:500}") int pollMillis,
            @Value("${xinkelink.cache.post-metrics.cdc.timeout-millis:5000}") int timeoutMillis,
            @Value("${xinkelink.cache.post-metrics.cdc.retry-initial-millis:250}") int retryInitial,
            @Value("${xinkelink.cache.post-metrics.cdc.retry-max-millis:5000}") int retryMax) {
        var settings=new PostMetricsCdcConsumer.Settings(enabled,host,port,destination,username,password,schema,
                checkpoint.isBlank()?null:Path.of(checkpoint),batchSize,pollMillis,timeoutMillis,retryInitial,retryMax);
        return new PostMetricsCdcConsumer(settings,()->{
            var connector=new SimpleCanalConnector(new InetSocketAddress(host,port),username,password,destination,timeoutMillis);
            connector.setRollbackOnConnect(false);
            return connector;
        },new PostMetricsEventMapper(enabled?schema:"disabled"),cache::invalidateConfirmed,meters);
    }
}
