package cn.xinkelink.statistics;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.*;
import org.springframework.context.SmartLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Every KafkaConsumer API except wakeup is called exclusively on its owner thread. */
public class ViewConsumer implements SmartLifecycle {
    private final ViewStatisticsProperties config;private final ViewProcessor processor;private final ViewFailures failures;private final MeterRegistry meters;
    private volatile boolean running; private volatile Consumer<String,byte[]> current; private Thread thread;
    private final AtomicInteger assigned=new AtomicInteger();
    public ViewConsumer(ViewStatisticsProperties config,ViewProcessor processor,ViewFailures failures,MeterRegistry meters){
        this.config=config;this.processor=processor;this.failures=failures;this.meters=meters;
        meters.gauge("xinkelink.view.consumer.partitions",assigned);
    }
    protected Consumer<String,byte[]> createConsumer(){return new KafkaConsumer<>(properties());}
    Map<String,Object> properties(){
        Map<String,Object> p=new HashMap<>();p.put("bootstrap.servers",config.getBootstrapServers());p.put("group.id",config.getConsumerGroup());
        p.put("enable.auto.commit",false);p.put("auto.offset.reset","none");p.put("key.deserializer",StringDeserializer.class);p.put("value.deserializer",ByteArrayDeserializer.class);
        p.put("max.poll.records",16);p.put("max.partition.fetch.bytes",65536);p.put("fetch.max.bytes",262144);p.put("isolation.level","read_committed");
        p.put("group.protocol","classic");p.put("session.timeout.ms",10000);p.put("heartbeat.interval.ms",3000);p.put("max.poll.interval.ms",120000);
        p.put("request.timeout.ms",15000);p.put("default.api.timeout.ms",10000);p.put("client.id","xl-view-consumer-"+UUID.randomUUID());return p;
    }
    @Override public synchronized void start(){
        if(running)return;if(thread!=null&&thread.isAlive())throw new IllegalStateException("View consumer still stopping");
        config.validate();if(!config.isConsumerEnabled())return;
        running=true;thread=new Thread(this::run,"post-view-consumer");thread.setDaemon(true);thread.start();
    }
    private void run(){
        while(running) {
            try(var consumer=createConsumer()) {
                synchronized(this){if(!running)return;current=consumer;}
                consumer.subscribe(List.of(config.getTopic()),new ConsumerRebalanceListener(){
                    @Override public void onPartitionsRevoked(Collection<TopicPartition> p){assigned.set(0);meters.counter("xinkelink.view.rebalance","stage","REVOKED").increment();}
                    @Override public void onPartitionsAssigned(Collection<TopicPartition> p){
                        var offsets=consumer.committed(new HashSet<>(p));
                        for(var partition:p)if(offsets.get(partition)==null){Long open=failures.firstOpen(partition.topic(),partition.partition());if(open!=null)consumer.seek(partition,open);else consumer.seekToBeginning(List.of(partition));}
                        assigned.set(p.size());meters.counter("xinkelink.view.rebalance","stage","ASSIGNED").increment();
                    }
                });
                while(running){var records=consumer.poll(Duration.ofMillis(config.getPollMillis()));dispatch(consumer,records,()->running);}
            } catch(OffsetOutOfRangeException e){
                try{e.offsetOutOfRangePartitions().forEach((p,o)->failures.failed(p.topic(),p.partition(),o,new byte[0],new IllegalArgumentException("OFFSET_UNAVAILABLE")));}
                catch(RuntimeException unavailable){meters.counter("xinkelink.view.failure.persistence.failed").increment();}
                meters.counter("xinkelink.view.consumer.failure").increment();
            }
            catch(WakeupException e){if(running)meters.counter("xinkelink.view.consumer.failure").increment();}
            catch(RuntimeException e){meters.counter("xinkelink.view.consumer.failure").increment();}
            finally {current=null;assigned.set(0);}
            if(running)try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
        }
    }
    /** Failure rewinds only its partition, never committing a later record from that partition. */
    void dispatch(Consumer<String,byte[]> consumer,ConsumerRecords<String,byte[]> records,BooleanSupplier active){
        boolean failed=false;
        for(var partition:records.partitions()) {
            for(var record:records.records(partition)) {
                if(!active.getAsBoolean())return;
                try {
                    if(failures.blockedBefore(record.topic(),record.partition(),record.offset()))throw new IllegalArgumentException("PRIOR_RECORD_BLOCKED");
                    long start=System.nanoTime();var result=processor.process(record.value()); // Spring proxy has committed here.
                    afterProcessed(record,result);
                    failures.resolved(record.topic(),record.partition(),record.offset());
                    consumer.commitSync(Map.of(partition,new OffsetAndMetadata(record.offset()+1)));
                    meters.counter("xinkelink.view.consume","result",result.name()).increment();
                    meters.timer("xinkelink.view.consume.duration","result",result.name()).record(System.nanoTime()-start,TimeUnit.NANOSECONDS);
                } catch(WakeupException e){throw e;}
                catch(RuntimeException e) {
                    // If failure persistence itself fails, escape and recreate from committed offsets.
                    failures.failed(record.topic(),record.partition(),record.offset(),record.value(),e);
                    consumer.seek(partition,record.offset());failed=true;break;
                }
            }
        }
        if(failed&&active.getAsBoolean())try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    protected void afterProcessed(ConsumerRecord<String,byte[]> record,ViewProcessor.Result result){ /* Actual post-commit/pre-offset boundary. */ }
    @Override public void stop(){Thread owned;
        synchronized(this){running=false;var c=current;if(c!=null)c.wakeup();owned=thread;}
        if(owned!=null&&owned!=Thread.currentThread())try{owned.join(60000);if(owned.isAlive())throw new IllegalStateException("View consumer has not stopped");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("View consumer stop interrupted");}
    }
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return 1100;}
}
