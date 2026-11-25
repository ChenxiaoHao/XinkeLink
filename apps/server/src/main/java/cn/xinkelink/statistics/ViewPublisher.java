package cn.xinkelink.statistics;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;

public class ViewPublisher {
    private final ViewJournal journal;private final ViewKafkaTransport transport;private final MeterRegistry meters;
    private long nextReconcile;
    public ViewPublisher(ViewJournal journal,ViewKafkaTransport transport,MeterRegistry meters){this.journal=journal;this.transport=transport;this.meters=meters;}
    public void publishBatch(BooleanSupplier active) {
        if(!active.getAsBoolean())return;
        if(System.nanoTime()>=nextReconcile){int recovered=journal.recoverMissing();meters.counter("xinkelink.view.receipt.requeued").increment(recovered);nextReconcile=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);}
        for(var c:journal.claim()) {
            if(!active.getAsBoolean())return;
            long start=System.nanoTime();String outcome=transport.send(c);
            afterSend(c,outcome);
            journal.finish(c,outcome);
            meters.counter("xinkelink.view.send","result",outcome).increment();
            meters.timer("xinkelink.view.send.duration","result",outcome).record(System.nanoTime()-start,TimeUnit.NANOSECONDS);
        }
    }
    protected void afterSend(ViewJournal.Claim c,String outcome){ /* Test worker may halt here after a real Kafka acknowledgement. */ }
}
