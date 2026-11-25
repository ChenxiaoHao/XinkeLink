package cn.xinkelink.notification;
import cn.xinkelink.notification.mq.*;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RocketMqTransportTest {
    @Test void closedTransportRejectsLateBatchAndOnlyExplicitLifecycleOpenCanCreateClient()throws Exception{
        var created=new AtomicInteger();var producer=mock(DefaultMQProducer.class);
        var transport=new RocketMqTransport(new NotificationMqProperties()){
            @Override protected DefaultMQProducer createProducer(){created.incrementAndGet();return producer;}
        };
        transport.close();var claim=new InteractionJournal.Claim("id","{}","token",1);
        assertThrows(IllegalStateException.class,()->transport.send(claim));assertEquals(0,created.get());
        transport.open();doThrow(new java.lang.IllegalArgumentException("test send reached")).when(producer).send(any(org.apache.rocketmq.common.message.Message.class),anyLong());
        assertThrows(IllegalArgumentException.class,()->transport.send(claim));assertEquals(1,created.get());transport.close();
        assertThrows(IllegalStateException.class,()->transport.send(claim));assertEquals(1,created.get());verify(producer).shutdown();
    }
}
