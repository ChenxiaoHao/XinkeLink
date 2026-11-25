package cn.xinkelink.notification.mq;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false) @Profile("auth")
@EnableConfigurationProperties(NotificationMqProperties.class)
public class NotificationMqConfiguration {
    @Bean RocketMqTransport notificationTransport(NotificationMqProperties config){return new RocketMqTransport(config);}
    @Bean OutboxPublisher notificationPublisher(InteractionJournal journal,RocketMqTransport transport,MeterRegistry meters){return new OutboxPublisher(journal,transport,meters);}
    @Bean NotificationMessageHandler notificationMessageHandler(InboxProcessor processor,NotificationFailures failures,NotificationMqProperties config,MeterRegistry meters){return new NotificationMessageHandler(processor,failures,config,meters);}
    @Bean NotificationDelivery notificationDelivery(NotificationMqProperties config,OutboxPublisher publisher,RocketMqTransport transport,NotificationMessageHandler handler,MeterRegistry meters,JdbcTemplate jdbc){return new NotificationDelivery(config,publisher,transport,handler,meters,jdbc);}
}
