package cn.xinkelink.notification.mq;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotificationMessageHandler {
    private static final Logger LOG=LoggerFactory.getLogger(NotificationMessageHandler.class);
    private final InboxProcessor processor;private final NotificationFailures failures;private final NotificationMqProperties config;private final MeterRegistry meters;
    public NotificationMessageHandler(InboxProcessor processor,NotificationFailures failures,NotificationMqProperties config,MeterRegistry meters){this.processor=processor;this.failures=failures;this.config=config;this.meters=meters;}
    public ConsumeConcurrentlyStatus handle(MessageExt message){
        try {
            // The Spring proxy completes the transaction (including commit) before this call returns.
            var result=processor.process(message.getBody());
            failures.resolve(message.getBody());
            meters.counter("xinkelink.notification.consume","result",result.name()).increment();
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch(RuntimeException e){
            boolean quarantined=message.getReconsumeTimes()>=config.getMaxReconsumeTimes();
            try{failures.record(message.getBody(),message.getReconsumeTimes(),quarantined);}
            catch(RuntimeException persistenceFailed){LOG.warn("Notification failure evidence unavailable; consumption remains unacknowledged");}
            meters.counter("xinkelink.notification.consume","result","RETRY").increment();
            if(quarantined)meters.counter("xinkelink.notification.failure.disposition").increment();
            LOG.warn("Notification consumption failed; retry scheduled (attempt={})",message.getReconsumeTimes());
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }
}
