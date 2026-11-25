package cn.xinkelink.forum.metrics.cdc;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** One designated native Canal consumer. Only confirmed shared invalidation permits ACK. */
public final class PostMetricsCdcConsumer implements SmartLifecycle, DisposableBean {
    public record Settings(boolean enabled, String host, int port, String destination, String username,
            String password, String schema, Path checkpoint, int batchSize, int pollMillis,
            int timeoutMillis, int retryInitialMillis, int retryMaxMillis) {
        public Settings {
            if(enabled && (host==null || !Set.of("127.0.0.1","localhost","::1").contains(host) || port<1 || port>65535
                    || destination==null || !destination.matches("[a-zA-Z0-9_]+")
                    || schema==null || !schema.matches("[a-zA-Z0-9_]+")
                    || checkpoint==null || !checkpoint.isAbsolute() || batchSize<1 || batchSize>4096
                    || pollMillis<10 || timeoutMillis<=pollMillis || retryInitialMillis<50 || retryMaxMillis<retryInitialMillis))
                throw new IllegalArgumentException("Invalid post metrics CDC configuration");
        }
        @Override public String toString() { return "PostMetricsCdcSettings[enabled="+enabled+", credentials=redacted]"; }
        public String filter() { return schema+"\\.xl_(like|comment|post)"; }
    }
    public enum State { DISABLED, CONNECTING, POLLING, RETRYING, BLOCKED, STOPPED }
    public enum Phase { CONNECT, POLL, MAP, INVALIDATE, CHECKPOINT, ACK, ROLLBACK, OWNER }
    public static final class ProcessingException extends RuntimeException {
        private final Phase phase;
        private ProcessingException(Phase phase) { super("CDC processing not confirmed: "+phase);this.phase=phase; }
        public Phase phase() { return phase; }
    }
    private static final Logger log=LoggerFactory.getLogger(PostMetricsCdcConsumer.class);
    private final Settings settings;
    private final Supplier<CanalConnector> factory;
    private final PostMetricsEventMapper mapper;
    private final Consumer<Collection<Long>> invalidator;
    private final MeterRegistry meters;
    private final CdcCheckpoint checkpoint;
    private final AtomicInteger state=new AtomicInteger();
    private final AtomicLong lastSuccess=new AtomicLong(), lastAckSent=new AtomicLong(), lag=new AtomicLong();
    private volatile boolean running;
    private volatile CanalConnector connection;
    private ExecutorService executor;

    public PostMetricsCdcConsumer(Settings settings, Supplier<CanalConnector> factory,
            PostMetricsEventMapper mapper, Consumer<Collection<Long>> invalidator, MeterRegistry meters) {
        this.settings=settings;this.factory=factory;this.mapper=mapper;this.invalidator=invalidator;this.meters=meters;
        this.checkpoint=settings.enabled ? new CdcCheckpoint(settings.checkpoint.normalize(),settings.destination,settings.schema,settings.port):null;
        state.set(settings.enabled?State.STOPPED.ordinal():State.DISABLED.ordinal());
        meters.gauge("post.metrics.cdc.state",state,AtomicInteger::get);
        meters.gauge("post.metrics.cdc.last.success.seconds",lastSuccess,value->value.get()/1000.0);
        meters.gauge("post.metrics.cdc.last.ack.sent.seconds",lastAckSent,value->value.get()/1000.0);
        meters.gauge("post.metrics.cdc.lag.millis",lag,AtomicLong::get);
    }

    /** All chunks must be confirmed before any acknowledgement is sent. No row values are logged. */
    public boolean processBatch(CanalConnector connector, Message batch) {
        if(!settings.enabled) throw new IllegalStateException("CDC is disabled");
        if(batch==null) throw failure(Phase.POLL);
        if(batch.getId()<0) return false;
        try {checkpoint.acquire();} catch(IOException failure) {throw failure(Phase.OWNER);}
        meters.counter("post.metrics.cdc.batches").increment();
        PostMetricsEventMapper.Mapping mapping;
        String position;
        try {
            if(batch.isRaw()) throw new IllegalArgumentException("Raw CDC batch requires decoding");
            mapping=mapper.map(batch.getEntries());position=position(batch.getEntries());
        }
        catch(RuntimeException failure) {throw failure(Phase.MAP);}
        meters.counter("post.metrics.cdc.events.relevant").increment(mapping.relevantEvents());
        meters.counter("post.metrics.cdc.posts.attempted").increment(mapping.postIds().size());
        List<Long> ids=new ArrayList<>(mapping.postIds());
        try {
            for(int offset=0;offset<ids.size();offset+=512)
                invalidator.accept(ids.subList(offset,Math.min(ids.size(),offset+512)));
        } catch(RuntimeException failure) {throw failure(Phase.INVALIDATE);}
        long now=System.currentTimeMillis();
        lastSuccess.set(now);
        if(mapping.maxExecuteTime()>0) lag.set(Math.max(0,now-mapping.maxExecuteTime()));
        meters.counter("post.metrics.cdc.processed").increment();
        try {checkpoint.save("PROCESSED",batch.getId(),position,ids.size(),mapping.relevantEvents(),mapping.maxExecuteTime());}
        catch(IOException failure) {throw failure(Phase.CHECKPOINT);}
        meters.counter("post.metrics.cdc.ack.attempts").increment();
        try {connector.ack(batch.getId());}
        catch(RuntimeException failure) {throw failure(Phase.ACK);}
        // Native ACK is a write-only protocol operation, not a durable server confirmation.
        lastAckSent.set(System.currentTimeMillis());
        meters.counter("post.metrics.cdc.ack.sent").increment();
        try {checkpoint.save("ACK_SENT",batch.getId(),position,ids.size(),mapping.relevantEvents(),mapping.maxExecuteTime());}
        catch(IOException failure) {throw failure(Phase.CHECKPOINT);}
        log.info("Post metrics CDC processed position={} events={} affected={} ack=sent",position,mapping.relevantEvents(),ids.size());
        return true;
    }

    private static String position(List<CanalEntry.Entry> entries) {
        if(entries==null || entries.isEmpty()) return "empty";
        var header=entries.getLast().getHeader();
        String file=header.getLogfileName();
        if(!file.matches("[a-zA-Z0-9_.-]{1,255}") || header.getLogfileOffset()<0)
            throw new IllegalArgumentException("Invalid CDC position");
        return file+":"+header.getLogfileOffset();
    }
    private ProcessingException failure(Phase phase) {
        meters.counter("post.metrics.cdc.failures","phase",phase.name().toLowerCase(Locale.ROOT)).increment();
        return new ProcessingException(phase);
    }
    @Override public synchronized void start() {
        // A timed-out stop may still have uninterruptible I/O in flight. Its owner/connection
        // must drain before this instance can create another native client with the same identity.
        if(!settings.enabled || running || (executor!=null && !executor.isTerminated())) return;
        running=true;
        executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"post-metrics-cdc");thread.setDaemon(true);return thread;});
        executor.execute(this::consume);
    }
    private void consume() {
        long backoff=settings.retryInitialMillis;
        try {
            while(running) {
                Phase phase=Phase.OWNER;
                long batchId=-1;
                try {
                    checkpoint.acquire();
                    transition(State.CONNECTING,Phase.CONNECT);
                    phase=Phase.CONNECT;
                    connection=factory.get();
                    connection.connect();
                    connection.subscribe(settings.filter());
                    connection.rollback(); // Resume only the server's confirmed client cursor, not a fetch pointer.
                    meters.counter("post.metrics.cdc.connections").increment();
                    transition(State.POLLING,Phase.POLL);
                    while(running) {
                        phase=Phase.POLL;
                        Message batch=connection.getWithoutAck(settings.batchSize,(long)settings.pollMillis,TimeUnit.MILLISECONDS);
                        batchId=batch==null?-1:batch.getId();
                        processBatch(connection,batch);
                        batchId=-1;
                        backoff=settings.retryInitialMillis;
                        if(batch.getId()<0) Thread.sleep(Math.min(settings.pollMillis,100));
                    }
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();break;
                } catch(Exception failed) {
                    if(!running) break;
                    Phase failurePhase=failed instanceof ProcessingException processed?processed.phase():phase;
                    if(!(failed instanceof ProcessingException)) failure(failurePhase);
                    transition(failurePhase==Phase.MAP || failurePhase==Phase.CHECKPOINT || failurePhase==Phase.OWNER
                            ?State.BLOCKED:State.RETRYING,failurePhase);
                    if(batchId>=0 && connection!=null) {
                        try {connection.rollback(batchId);meters.counter("post.metrics.cdc.rollbacks").increment();}
                        catch(RuntimeException ignored) {failure(Phase.ROLLBACK);}
                    }
                } finally {disconnect();}
                if(running) {
                    meters.counter("post.metrics.cdc.retries").increment();
                    try {Thread.sleep(backoff);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();break;}
                    backoff=Math.min(settings.retryMaxMillis,backoff*2);
                }
            }
        } finally {
            disconnect();
            try {if(checkpoint!=null) checkpoint.close();} catch(IOException ignored) {failure(Phase.OWNER);}
        }
    }
    private void transition(State next, Phase phase) {
        if(state.getAndSet(next.ordinal())!=next.ordinal()) log.info("Post metrics CDC state={} phase={}",next,phase);
        // A failed owner must not overwrite the active owner's status file.
        if(next!=State.BLOCKED || phase!=Phase.OWNER) {
            try {checkpoint.status(next.name(),phase.name());} catch(IOException ignored) {failure(Phase.CHECKPOINT);}
        }
    }
    private void disconnect() {
        CanalConnector current=connection;connection=null;
        if(current!=null) try {current.disconnect();} catch(RuntimeException ignored) { /* retried from the server cursor */ }
    }
    @Override public synchronized void stop() {
        running=false;
        ExecutorService current=executor;
        disconnect();
        boolean terminated=current==null;
        if(current!=null) {
            current.shutdownNow();
            try {terminated=current.awaitTermination(6,TimeUnit.SECONDS);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        }
        // A slow in-flight owner retains its lock until its worker's finally block finishes.
        try {if(terminated && checkpoint!=null) checkpoint.close();} catch(IOException ignored) {failure(Phase.OWNER);}
        if(terminated) executor=null;
        state.set(settings.enabled?State.STOPPED.ordinal():State.DISABLED.ordinal());
    }
    @Override public boolean isRunning() {return running;}
    @Override public void destroy() {stop();}
    public State state() {return State.values()[state.get()];}
}
