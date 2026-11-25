package cn.xinkelink.notification;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class RocketMqCompatibilityVerification {
    @Test void nativeRemotingClientStartsAgainstIsolatedBroker()throws Exception{
        var c=new DefaultMQPushConsumer("xl_compatibility_"+UUID.randomUUID());
        c.setNamesrvAddr(System.getenv("XINKELINK_NOTIFICATION_MQ_NAMESRV_ADDR"));c.setInstanceName(UUID.randomUUID().toString());c.setVipChannelEnabled(false);
        c.subscribe(System.getenv("XINKELINK_NOTIFICATION_MQ_TOPIC"),"*");c.registerMessageListener((MessageListenerConcurrently)(messages,context)->ConsumeConcurrentlyStatus.RECONSUME_LATER);
        try{assertDoesNotThrow(c::start);assertFalse(c.fetchSubscribeMessageQueues(System.getenv("XINKELINK_NOTIFICATION_MQ_TOPIC")).isEmpty());}finally{c.shutdown();}
    }
}
