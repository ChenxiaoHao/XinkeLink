package cn.xinkelink.messaging;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentCursor;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.notification.NotificationService;
import cn.xinkelink.realtime.RealtimeEvents;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagingRetainedMessageTest {
    @Test void retainedTextRetryIgnoresRetiredContextHashButStillRejectsDifferentPayload() {
        var store=mock(MessagingStore.class);
        var support=mock(ContentSupport.class);
        var actor=new AuthPrincipal(10,1,"user");
        var now=Instant.now();
        var conversation=new MessagingStore.Conversation(7,10,20,BigInteger.TWO,1L,now,now,
                BigInteger.ZERO,BigInteger.ZERO,false,false,0);
        // Retained messages can carry a hash of the old request containing retired context fields.
        var oldMessage=new MessagingStore.Message(1,7,10,BigInteger.ONE,"retained-key",new byte[32],"原来的私信","normal",now);
        when(support.require(actor,"message")).thenReturn(10L);
        when(store.conversation(7,10,true)).thenReturn(conversation);
        when(store.request(10,"retained-key")).thenReturn(oldMessage);
        var service=new MessagingService(store,support,mock(ContentCursor.class),
                mock(NotificationService.class),mock(RealtimeEvents.class));
        var retry=service.send(7,new MessagingModels.SendMessage("原来的私信","retained-key"),actor);
        assertEquals("1",retry.get("id"));
        assertFalse(retry.containsKey("product"));
        var conflict=assertThrows(BusinessException.class,()->service.send(7,new MessagingModels.SendMessage("不同正文","retained-key"),actor));
        assertEquals(ErrorCode.DUPLICATE_REQUEST_CONFLICT,conflict.code());
        verify(store,never()).insert(any(),anyLong(),anyString(),any(),anyString());
    }
}
