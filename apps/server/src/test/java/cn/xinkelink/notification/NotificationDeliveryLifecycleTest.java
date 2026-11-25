package cn.xinkelink.notification;

import cn.xinkelink.notification.mq.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationDeliveryLifecycleTest {
    @Test void actualBrokerProbeFailureAndRecoveryUpdateMetricsAfterStartup()throws Exception{
        var config=new NotificationMqProperties();config.setConsumerEnabled(true);var consumer=mock(DefaultMQPushConsumer.class);
        var implementation=mock(org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl.class);
        var factory=mock(org.apache.rocketmq.client.impl.factory.MQClientInstance.class);var api=mock(org.apache.rocketmq.client.impl.MQClientAPIImpl.class);
        when(consumer.fetchSubscribeMessageQueues(config.getTopic())).thenReturn(java.util.Set.of(new org.apache.rocketmq.common.message.MessageQueue(config.getTopic(),"test-broker",0)));
        when(consumer.getDefaultMQPushConsumerImpl()).thenReturn(implementation);when(implementation.getmQClientFactory()).thenReturn(factory);when(factory.findBrokerAddressInPublish("test-broker")).thenReturn("127.0.0.1:20911");when(factory.getMQClientAPIImpl()).thenReturn(api);
        CountDownLatch probed=new CountDownLatch(1);when(api.getBrokerRuntimeInfo(anyString(),anyLong())).thenReturn(new org.apache.rocketmq.remoting.protocol.body.KVTable());
        var meters=new SimpleMeterRegistry();var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Double.class))).thenReturn(0d);
        var delivery=new NotificationDelivery(config,mock(OutboxPublisher.class),mock(RocketMqTransport.class),mock(NotificationMessageHandler.class),meters,jdbc){
            @Override protected DefaultMQPushConsumer createConsumer(){return consumer;}
            @Override protected void probeBroker(){super.probeBroker();probed.countDown();}
            void probe(){super.probeBroker();}
        };
        try{delivery.start();assertTrue(probed.await(3,TimeUnit.SECONDS));
            doThrow(new org.apache.rocketmq.remoting.exception.RemotingConnectException("test-broker")).when(api).getBrokerRuntimeInfo(anyString(),anyLong());delivery.probe();
            assertEquals(0,meters.get("xinkelink.notification.broker.reachable").gauge().value());assertEquals(2,meters.get("xinkelink.notification.consumer.state").gauge().value());
            doReturn(new org.apache.rocketmq.remoting.protocol.body.KVTable()).when(api).getBrokerRuntimeInfo(anyString(),anyLong());delivery.probe();
            assertEquals(1,meters.get("xinkelink.notification.broker.reachable").gauge().value());assertEquals(2,meters.get("xinkelink.notification.consumer.state").gauge().value());assertEquals(1,meters.get("xinkelink.notification.broker.recovery").timer().count());
        }finally{delivery.stop();}
    }
    @Test void healthyBrokerCannotClearProcessingRetryOrInventConsumerRecovery()throws Exception{
        var config=new NotificationMqProperties();config.setConsumerEnabled(true);var consumer=mock(DefaultMQPushConsumer.class);
        var implementation=mock(org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl.class);var factory=mock(org.apache.rocketmq.client.impl.factory.MQClientInstance.class);var api=mock(org.apache.rocketmq.client.impl.MQClientAPIImpl.class);
        when(consumer.fetchSubscribeMessageQueues(config.getTopic())).thenReturn(java.util.Set.of(new org.apache.rocketmq.common.message.MessageQueue(config.getTopic(),"broker",0)));
        when(consumer.getDefaultMQPushConsumerImpl()).thenReturn(implementation);when(implementation.getmQClientFactory()).thenReturn(factory);when(factory.findBrokerAddressInPublish("broker")).thenReturn("127.0.0.1:20911");when(factory.getMQClientAPIImpl()).thenReturn(api);when(api.getBrokerRuntimeInfo(anyString(),anyLong())).thenReturn(new org.apache.rocketmq.remoting.protocol.body.KVTable());
        var meters=new SimpleMeterRegistry();var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Double.class))).thenReturn(0d);var handler=mock(NotificationMessageHandler.class);var message=new org.apache.rocketmq.common.message.MessageExt();
        when(handler.handle(message)).thenReturn(org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.RECONSUME_LATER);CountDownLatch ready=new CountDownLatch(1);
        var delivery=new NotificationDelivery(config,mock(OutboxPublisher.class),mock(RocketMqTransport.class),handler,meters,jdbc){
            @Override protected DefaultMQPushConsumer createConsumer(){return consumer;}
            @Override protected void probeBroker(){super.probeBroker();ready.countDown();}
            void probe(){super.probeBroker();}
        };
        try{delivery.start();assertTrue(ready.await(3,TimeUnit.SECONDS));var capture=org.mockito.ArgumentCaptor.forClass(org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently.class);verify(consumer).registerMessageListener(capture.capture());
            var listener=capture.getValue();var context=mock(org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext.class);
            listener.consumeMessage(java.util.List.of(message),context);assertEquals(4,meters.get("xinkelink.notification.consumer.state").gauge().value());delivery.probe();
            assertEquals(4,meters.get("xinkelink.notification.consumer.state").gauge().value());assertNull(meters.find("xinkelink.notification.consumer.recovery").timer());
            when(handler.handle(message)).thenReturn(org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS);listener.consumeMessage(java.util.List.of(message),context);
            assertEquals(3,meters.get("xinkelink.notification.consumer.state").gauge().value());assertEquals(1,meters.get("xinkelink.notification.consumer.recovery").timer().count());
        }finally{delivery.stop();}
    }
    @Test void consumerCompletingStartupAfterStopIsClosedAndCannotPublish()throws Exception{
        var config=new NotificationMqProperties();config.setConsumerEnabled(true);config.setPublisherEnabled(true);config.setPollMillis(50);
        var consumer=mock(DefaultMQPushConsumer.class);var publisher=mock(OutboxPublisher.class);var transport=mock(RocketMqTransport.class);var meters=new SimpleMeterRegistry();
        CountDownLatch starting=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        doAnswer(invocation->{starting.countDown();boolean done=false;while(!done)try{done=release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return null;}).when(consumer).start();
        doAnswer(invocation->{closed.countDown();return null;}).when(consumer).shutdown();
        var delivery=new NotificationDelivery(config,publisher,transport,mock(NotificationMessageHandler.class),meters,mock(JdbcTemplate.class)){
            @Override protected DefaultMQPushConsumer createConsumer(){return consumer;}
        };
        delivery.start();assertTrue(starting.await(3,TimeUnit.SECONDS));
        try{Thread.currentThread().interrupt();delivery.stop();}finally{Thread.interrupted();release.countDown();}
        assertTrue(closed.await(3,TimeUnit.SECONDS),"late native consumer must close itself");
        verify(publisher,never()).publishBatch();assertFalse(delivery.isRunning());assertEquals(0,meters.get("xinkelink.notification.consumer.state").gauge().value());
    }
}
