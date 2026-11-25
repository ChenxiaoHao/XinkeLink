package cn.xinkelink.statistics;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ViewConsumerTest {
    @Test void durableEarlierFailureBlocksLaterKafkaRecordsEvenAfterBrokerRetentionOrOffsetReset() {
        var p=mock(ViewProcessor.class);var f=mock(ViewFailures.class);var worker=new ViewConsumer(new ViewStatisticsProperties(),p,f,new SimpleMeterRegistry());
        @SuppressWarnings("unchecked") Consumer<String,byte[]> c=mock(Consumer.class);var tp=new TopicPartition("views",0);byte[] next={1};
        when(f.blockedBefore("views",0,20)).thenReturn(true);
        worker.dispatch(c,new ConsumerRecords<>(Map.of(tp,List.of(new ConsumerRecord<>("views",0,20,"1",next))),Map.of()),()->true);
        verifyNoInteractions(p);verify(c,never()).commitSync(anyMap());verify(c).seek(tp,20);
        assertEquals("none",worker.properties().get("auto.offset.reset"));
    }
    @Test void failedRecordCannotCommitAHigherOffsetInItsPartitionButOtherPartitionCanAdvance() {
        var p=mock(ViewProcessor.class);var f=mock(ViewFailures.class);var config=new ViewStatisticsProperties();
        var worker=new ViewConsumer(config,p,f,new SimpleMeterRegistry());
        @SuppressWarnings("unchecked") Consumer<String,byte[]> c=mock(Consumer.class);
        var a=new TopicPartition("views",0);var b=new TopicPartition("views",1);
        byte[] ok={1},bad={2},later={3},other={4};when(p.process(ok)).thenReturn(ViewProcessor.Result.APPLIED);
        when(p.process(bad)).thenThrow(new IllegalStateException("fail"));when(p.process(other)).thenReturn(ViewProcessor.Result.APPLIED);
        var records=new ConsumerRecords<String,byte[]>(Map.of(a,List.of(new ConsumerRecord<>("views",0,10,"1",ok),new ConsumerRecord<>("views",0,11,"1",bad),new ConsumerRecord<>("views",0,12,"1",later)),b,List.of(new ConsumerRecord<>("views",1,5,"2",other))),Map.of());
        worker.dispatch(c,records,()->true);
        verify(c).commitSync(Map.of(a,new OffsetAndMetadata(11)));
        verify(c).commitSync(Map.of(b,new OffsetAndMetadata(6)));
        verify(c).seek(a,11);verify(p,never()).process(later);
        verify(f).failed(eq("views"),eq(0),eq(11L),same(bad),any());
        verify(c,times(2)).commitSync(anyMap());
    }
    @Test void transactionReturnMustPrecedeOffsetAndStopCannotStartMoreRecords() {
        var p=mock(ViewProcessor.class);var f=mock(ViewFailures.class);var flag=new AtomicBoolean(true);
        var worker=new ViewConsumer(new ViewStatisticsProperties(),p,f,new SimpleMeterRegistry());
        @SuppressWarnings("unchecked") Consumer<String,byte[]> c=mock(Consumer.class);
        var tp=new TopicPartition("views",0);byte[] one={1},two={2};
        when(p.process(one)).thenAnswer(call->{flag.set(false);return ViewProcessor.Result.APPLIED;});
        var records=new ConsumerRecords<String,byte[]>(Map.of(tp,List.of(new ConsumerRecord<>("views",0,0,"1",one),new ConsumerRecord<>("views",0,1,"1",two))),Map.of());
        worker.dispatch(c,records,flag::get);
        var ordered=inOrder(p,f,c);ordered.verify(p).process(one);ordered.verify(f).resolved("views",0,0);ordered.verify(c).commitSync(Map.of(tp,new OffsetAndMetadata(1)));
        verify(p,never()).process(two);
    }
    @Test void commitUnknownIsRewoundAndDuplicateWillBeTheOnlyEffect() {
        var p=mock(ViewProcessor.class);var f=mock(ViewFailures.class);var worker=new ViewConsumer(new ViewStatisticsProperties(),p,f,new SimpleMeterRegistry());
        @SuppressWarnings("unchecked") Consumer<String,byte[]> c=mock(Consumer.class);var tp=new TopicPartition("views",0);byte[] one={1};
        when(p.process(one)).thenReturn(ViewProcessor.Result.APPLIED,ViewProcessor.Result.DUPLICATE);
        doThrow(new CommitFailedException()).doNothing().when(c).commitSync(anyMap());
        var batch=new ConsumerRecords<String,byte[]>(Map.of(tp,List.of(new ConsumerRecord<>("views",0,3,"1",one))),Map.of());
        worker.dispatch(c,batch,()->true);verify(c).seek(tp,3);worker.dispatch(c,batch,()->true);verify(p,times(2)).process(one);
    }
}
