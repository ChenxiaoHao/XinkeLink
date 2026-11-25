package cn.xinkelink.forum.metrics.cdc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import com.google.protobuf.ByteString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class PostMetricsCdcConsumerTest {
    @TempDir Path temp;
    private final CanalConnector transport=mock(CanalConnector.class);
    private final PostMetricsEventMapper mapping=mock(PostMetricsEventMapper.class);
    @SuppressWarnings("unchecked") private final Consumer<Collection<Long>> invalidation=mock(Consumer.class);
    private final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    private PostMetricsCdcConsumer make() {
        return make(metrics);
    }
    private PostMetricsCdcConsumer make(SimpleMeterRegistry registry) {
        return new PostMetricsCdcConsumer(new PostMetricsCdcConsumer.Settings(true,"127.0.0.1",11111,"test","user","private-secret","cache_test",
                temp.resolve("checkpoint"),100,100,2000,100,1000),()->transport,mapping,invalidation,registry);
    }
    private Message batch() {
        return new Message(7,List.of(CanalEntry.Entry.newBuilder().setHeader(CanalEntry.Header.newBuilder()
                .setLogfileName("mysql-bin.000001").setLogfileOffset(4)).setEntryType(CanalEntry.EntryType.TRANSACTIONEND).build()));
    }
    private Properties checkpoint() throws Exception {
        var p=new Properties();try(var reader=Files.newBufferedReader(temp.resolve("checkpoint"))){p.load(reader);}return p;
    }
    private void normal() {when(mapping.map(anyList())).thenReturn(new PostMetricsEventMapper.Mapping(Set.of(17L),1,System.currentTimeMillis()));}
    @Test void confirmationAndDurableProcessedAuditPrecedeAck() throws Exception {
        normal();var consumer=make();
        doAnswer(call->{assertEquals("PROCESSED",checkpoint().getProperty("phase"));return null;}).when(transport).ack(7);
        try {
            assertTrue(consumer.processBatch(transport,batch()));
            var order=inOrder(invalidation,transport);order.verify(invalidation).accept(argThat(ids->new HashSet<>(ids).equals(Set.of(17L))));order.verify(transport).ack(7);
            assertEquals("ACK_SENT",checkpoint().getProperty("phase"));
            assertEquals(1,metrics.counter("post.metrics.cdc.ack.sent").count());
        } finally {consumer.stop();}
    }
    @Test void parseFailureCannotBeAcknowledged() {
        when(mapping.map(anyList())).thenThrow(new IllegalArgumentException("row details must not escape"));var consumer=make();
        try {
            var failure=assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->consumer.processBatch(transport,batch()));
            assertEquals(PostMetricsCdcConsumer.Phase.MAP,failure.phase());
            assertFalse(failure.getMessage().contains("row details"));verifyNoInteractions(invalidation);verify(transport,never()).ack(anyLong());
        } finally {consumer.stop();}
    }
    @Test void partialChunkFailureCannotConfirmWholeBatch() {
        var ids=new LinkedHashSet<>(LongStream.rangeClosed(1,600).boxed().toList());
        when(mapping.map(anyList())).thenReturn(new PostMetricsEventMapper.Mapping(ids,600,System.currentTimeMillis()));
        doNothing().doThrow(new IllegalStateException()).when(invalidation).accept(anyCollection());var consumer=make();
        try {
            var failure=assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->consumer.processBatch(transport,batch()));
            assertEquals(PostMetricsCdcConsumer.Phase.INVALIDATE,failure.phase());verify(invalidation,times(2)).accept(anyCollection());verify(transport,never()).ack(anyLong());
        } finally {consumer.stop();}
    }
    @Test void uncertainAckRetainsProcessedAuditForSafeReplay() throws Exception {
        normal();doThrow(new IllegalStateException()).when(transport).ack(7);var consumer=make();
        try {
            var failure=assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->consumer.processBatch(transport,batch()));
            assertEquals(PostMetricsCdcConsumer.Phase.ACK,failure.phase());assertEquals("PROCESSED",checkpoint().getProperty("phase"));
            assertEquals(0,metrics.counter("post.metrics.cdc.ack.sent").count());
        } finally {consumer.stop();}
    }
    @Test void checkpointFailureAfterConfirmedInvalidationMustNotAck() {
        normal();doAnswer(call->{Files.createDirectory(temp.resolve("checkpoint"));return null;}).when(invalidation).accept(anyCollection());var consumer=make();
        try {
            var failure=assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->consumer.processBatch(transport,batch()));
            assertEquals(PostMetricsCdcConsumer.Phase.CHECKPOINT,failure.phase());verify(transport,never()).ack(anyLong());
        } finally {consumer.stop();}
    }
    @Test void unrelatedEventsCanAckWithoutInvalidatingPosts() {
        when(mapping.map(anyList())).thenReturn(new PostMetricsEventMapper.Mapping(Set.of(),0,0));var consumer=make();
        try {assertTrue(consumer.processBatch(transport,batch()));verifyNoInteractions(invalidation);verify(transport).ack(7);}
        finally {consumer.stop();}
    }
    @Test void repeatedBatchReprocessesRatherThanDeduplicatingUncertainWorkAway() {
        normal();var consumer=make();
        try {consumer.processBatch(transport,batch());consumer.processBatch(transport,batch());verify(invalidation,times(2)).accept(anyCollection());verify(transport,times(2)).ack(7);}
        finally {consumer.stop();}
    }
    @Test void rawUnparsedBatchMustNotBeAcknowledgedAsEmpty() {
        var consumer=make();try {
            assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->consumer.processBatch(transport,new Message(9,true,List.of(ByteString.EMPTY))));
            verify(transport,never()).ack(anyLong());verifyNoInteractions(invalidation,mapping);
        } finally {consumer.stop();}
    }
    @Test void emptyPollDoesNotCreateCheckpointOrAck() {
        var consumer=make();try {assertFalse(consumer.processBatch(transport,new Message(-1,List.of())));assertFalse(Files.exists(temp.resolve("checkpoint")));verifyNoInteractions(transport,mapping,invalidation);}
        finally {consumer.stop();}
    }
    @Test void duplicateLocalOwnerCannotUseSameDestinationCheckpoint() {
        normal();var first=make();var second=make(new SimpleMeterRegistry());
        try {
            first.processBatch(transport,batch());
            assertEquals(PostMetricsCdcConsumer.Phase.OWNER,assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->second.processBatch(transport,batch())).phase());
            first.stop();assertTrue(second.processBatch(transport,batch()));
        } finally {first.stop();second.stop();}
    }
    @Test void differentCheckpointDoesNotAllowDuplicateNativeClientIdentity() {
        normal();var first=make();
        var settings=new PostMetricsCdcConsumer.Settings(true,"localhost",11111,"test","","","cache_test",temp.resolve("other"),100,100,2000,100,1000);
        var second=new PostMetricsCdcConsumer(settings,()->transport,mapping,invalidation,new SimpleMeterRegistry());
        try {
            first.processBatch(transport,batch());
            assertEquals(PostMetricsCdcConsumer.Phase.OWNER,assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->second.processBatch(transport,batch())).phase());
        } finally {first.stop();second.stop();}
    }
    @Test void checkpointIdentityMismatchIsNotSilentlyResetOnRetry() throws Exception {
        Files.writeString(temp.resolve("checkpoint"),"destination=other\nschema=cache_test\nclient_id=1001\n");normal();var consumer=make();
        try {
            for(int i=0;i<2;i++) assertEquals(PostMetricsCdcConsumer.Phase.OWNER,assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->consumer.processBatch(transport,batch())).phase());
            assertTrue(Files.readString(temp.resolve("checkpoint")).contains("destination=other"));verify(transport,never()).ack(anyLong());
        } finally {consumer.stop();}
    }
    @Test void positionIsValidatedBeforeItCanReachLogsOrCheckpoint() {
        normal();var consumer=make();var malformed=new Message(8,List.of(CanalEntry.Entry.newBuilder().setHeader(CanalEntry.Header.newBuilder().setLogfileName("private\nvalue")).build()));
        try {assertThrows(PostMetricsCdcConsumer.ProcessingException.class,()->consumer.processBatch(transport,malformed));verify(transport,never()).ack(anyLong());verifyNoInteractions(invalidation);}
        finally {consumer.stop();}
    }
    @Test void disabledLifecycleDoesNotConnect() {
        var disabled=new PostMetricsCdcConsumer.Settings(false,null,0,null,"private-user","private-password",null,null,0,0,0,0,0);
        assertFalse(disabled.toString().contains("private-user"));assertFalse(disabled.toString().contains("private-password"));
        var consumer=new PostMetricsCdcConsumer(disabled,()->{throw new AssertionError("disabled");},mapping,invalidation,metrics);
        consumer.start();assertFalse(consumer.isRunning());assertEquals(PostMetricsCdcConsumer.State.DISABLED,consumer.state());consumer.stop();
    }
    @Test void unavailableCanalDoesNotBlockStartAndCanBeStopped() throws Exception {
        var attempt=new CountDownLatch(1);
        var consumer=new PostMetricsCdcConsumer.Settings(true,"127.0.0.1",11111,"test","","","cache_test",temp.resolve("checkpoint"),100,100,2000,100,1000);
        var worker=new PostMetricsCdcConsumer(consumer,()->{attempt.countDown();throw new IllegalStateException("offline");},mapping,invalidation,metrics);
        try {assertDoesNotThrow(worker::start);assertTrue(attempt.await(3,TimeUnit.SECONDS));}
        finally {worker.stop();}
        assertFalse(worker.isRunning());assertEquals(PostMetricsCdcConsumer.State.STOPPED,worker.state());
    }
    @Test void timedOutStopCannotStartAnotherWorkerWhileOldInvalidationStillRuns() throws Exception {
        normal();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(transport.getWithoutAck(anyInt(),anyLong(),any())).thenReturn(batch());
        doAnswer(call->{
            entered.countDown();
            boolean done=false;
            while(!done) try {release.await();done=true;} catch(InterruptedException ignored) { /* model uninterruptible I/O */ }
            return null;
        }).when(invalidation).accept(anyCollection());
        var consumer=make();
        try {
            consumer.start();assertTrue(entered.await(3,TimeUnit.SECONDS));
            consumer.stop(); // Its bounded wait expires while the original owner is still blocked.
            consumer.start();
            assertFalse(consumer.isRunning(),"A draining worker must prevent a second native client owner");
            verify(transport,times(1)).connect();
        } finally {release.countDown();consumer.stop();}
    }
    @Test void sharedFailureDoesNotAcknowledge() {
        var connector = mock(CanalConnector.class);
        var mapper = mock(PostMetricsEventMapper.class);
        when(mapper.map(anyList())).thenReturn(new PostMetricsEventMapper.Mapping(Set.of(17L),1,System.currentTimeMillis()));
        var settings = new PostMetricsCdcConsumer.Settings(true,"127.0.0.1",11111,"test","","","cache_test",
                temp.resolve("checkpoint"),100,100,2000,100,1000);
        var consumer = new PostMetricsCdcConsumer(settings,()->connector,mapper, ids->{throw new IllegalStateException("not confirmed");},new SimpleMeterRegistry());
        try {
            assertThrows(PostMetricsCdcConsumer.ProcessingException.class, ()->consumer.processBatch(connector,new Message(7,List.of())));
            verify(connector,never()).ack(anyLong());
        } finally { consumer.stop(); }
    }
    @Test void explicitBatchProcessingContractMustExistBeforeAcknowledgement() {
        assertDoesNotThrow(() -> Class.forName("cn.xinkelink.forum.metrics.cdc.PostMetricsCdcConsumer"),
                "CDC needs a synchronous failure-aware batch processor before it can acknowledge Canal");
    }
}
