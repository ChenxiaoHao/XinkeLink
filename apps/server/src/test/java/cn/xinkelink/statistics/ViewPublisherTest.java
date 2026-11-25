package cn.xinkelink.statistics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ViewPublisherTest {
    @Test void unknownSendIsRetainedAndSuccessfulRetryKeepsOriginalEventBytes() {
        var journal=mock(ViewJournal.class);var transport=mock(ViewKafkaTransport.class);var meters=new SimpleMeterRegistry();
        var claim=new ViewJournal.Claim(UUID.randomUUID().toString(),123,"immutable intent",UUID.randomUUID().toString(),1);
        when(journal.claim()).thenReturn(List.of(claim));when(transport.send(claim)).thenReturn("UNKNOWN","OK");
        var publisher=new ViewPublisher(journal,transport,meters);publisher.publishBatch(()->true);publisher.publishBatch(()->true);
        verify(journal).finish(claim,"UNKNOWN");verify(journal).finish(claim,"OK");verify(transport,times(2)).send(claim);
        assertEquals(1,meters.counter("xinkelink.view.send","result","UNKNOWN").count());
    }
    @Test void stoppingLeavesUnsentClaimsForLeaseRecovery() {
        var journal=mock(ViewJournal.class);var transport=mock(ViewKafkaTransport.class);
        var publisher=new ViewPublisher(journal,transport,new SimpleMeterRegistry());publisher.publishBatch(()->false);
        verifyNoInteractions(journal,transport);
    }
}
