package cn.xinkelink.notification.mq;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;

/** Orchestration is intentionally not transactional; journal methods have independent short transactions. */
public class OutboxPublisher {
    @FunctionalInterface public interface Sender {Receipt send(InteractionJournal.Claim claim) throws Exception;}
    public record Receipt(String result,String brokerId){}
    private final InteractionJournal journal;private final Sender sender;private final MeterRegistry meters;
    public OutboxPublisher(InteractionJournal journal,Sender sender,MeterRegistry meters){this.journal=journal;this.sender=sender;this.meters=meters;}
    public int publishBatch(){
        var batch=journal.claim();
        for(var c:batch){long begin=System.nanoTime();Receipt receipt;
            try {receipt=sender.send(c);}
            catch(InterruptedException e){Thread.currentThread().interrupt();return batch.size();}
            catch(Exception e){receipt=new Receipt("UNKNOWN",null);}
            String result=receipt.result();
            if(!java.util.Set.of("OK","NOT_STORED","UNKNOWN").contains(result))result="UNKNOWN";
            meters.counter("xinkelink.notification.send","result",result).increment();
            meters.timer("xinkelink.notification.send.duration","result",result).record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);
            // A completion/commit exception must escape: leave the lease for replay, don't manufacture a different send result.
            if(!journal.finish(c,result,receipt.brokerId()))meters.counter("xinkelink.notification.publisher.fenced").increment();
        }
        return batch.size();
    }
}
