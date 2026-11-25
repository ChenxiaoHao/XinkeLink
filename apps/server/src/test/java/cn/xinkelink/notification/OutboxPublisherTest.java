package cn.xinkelink.notification;

import cn.xinkelink.notification.mq.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    final InteractionJournal journal=mock(InteractionJournal.class);
    final SimpleMeterRegistry meters=new SimpleMeterRegistry();
    final InteractionJournal.Claim claim=new InteractionJournal.Claim("stable-event-id","immutable-payload","lease-token",1);
    @Test void explicitSuccessFinishesAfterSendOutsideTransaction(){when(journal.claim()).thenReturn(List.of(claim));
        var publisher=new OutboxPublisher(journal,c->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(claim,c);return new OutboxPublisher.Receipt("OK","broker-id");},meters);
        publisher.publishBatch();var order=inOrder(journal);order.verify(journal).claim();order.verify(journal).finish(claim,"OK","broker-id");
    }
    @Test void unknownResultRetainsEventAndUsesUnknownRetry(){when(journal.claim()).thenReturn(List.of(claim));
        new OutboxPublisher(journal,c->{throw new java.io.IOException("injected timeout");},meters).publishBatch();verify(journal).finish(claim,"UNKNOWN",null);
    }
    @Test void nonSuccessDiskFlushStatusCannotMarkSent(){when(journal.claim()).thenReturn(List.of(claim));
        new OutboxPublisher(journal,c->new OutboxPublisher.Receipt("NOT_STORED",null),meters).publishBatch();verify(journal).finish(claim,"NOT_STORED",null);
    }
    @Test void failedCompletionAfterBrokerSuccessDoesNotLieAboutFailureResult(){when(journal.claim()).thenReturn(List.of(claim));doThrow(new IllegalStateException("DB commit failed")).when(journal).finish(claim,"OK","broker");
        assertThrows(IllegalStateException.class,()->new OutboxPublisher(journal,c->new OutboxPublisher.Receipt("OK","broker"),meters).publishBatch());verify(journal,never()).finish(claim,"UNKNOWN",null);
    }
}
