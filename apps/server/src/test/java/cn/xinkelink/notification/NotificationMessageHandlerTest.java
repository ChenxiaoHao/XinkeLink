package cn.xinkelink.notification;

import cn.xinkelink.notification.mq.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationMessageHandlerTest {
    @Test void successIsReturnedOnlyAfterProxiedProcessorReturns(){var processor=mock(InboxProcessor.class);var failures=mock(NotificationFailures.class);var config=new NotificationMqProperties();
        MessageExt msg=message();when(processor.process(msg.getBody())).thenReturn(InboxProcessor.Result.APPLIED);
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS,new NotificationMessageHandler(processor,failures,config,new SimpleMeterRegistry()).handle(msg));
        verify(processor).process(msg.getBody());verify(failures).resolve(msg.getBody());
    }
    @Test void commitFailureIsRetriedAndDurablyRecorded(){var processor=mock(InboxProcessor.class);var failures=mock(NotificationFailures.class);MessageExt msg=message();
        doThrow(new org.springframework.transaction.TransactionSystemException("commit failed")).when(processor).process(msg.getBody());
        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER,new NotificationMessageHandler(processor,failures,new NotificationMqProperties(),new SimpleMeterRegistry()).handle(msg));
        verify(failures).record(msg.getBody(),0,false);verify(failures,never()).resolve(any());
    }
    @Test void quarantinePersistenceFailureNeverAcknowledgesPoisonMessage(){var processor=mock(InboxProcessor.class);var failures=mock(NotificationFailures.class);MessageExt msg=message();msg.setReconsumeTimes(5);
        doThrow(new IllegalArgumentException("poison")).when(processor).process(msg.getBody());doThrow(new IllegalStateException("DB down")).when(failures).record(msg.getBody(),5,true);
        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER,new NotificationMessageHandler(processor,failures,new NotificationMqProperties(),new SimpleMeterRegistry()).handle(msg));
    }
    private MessageExt message(){var msg=new MessageExt();msg.setBody("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));return msg;}
}
