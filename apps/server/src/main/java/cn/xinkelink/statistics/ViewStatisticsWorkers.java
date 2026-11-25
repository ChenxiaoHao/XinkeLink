package cn.xinkelink.statistics;

import org.springframework.context.SmartLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.*;

/** Publisher and projection have separate bounded workers, so a broker outage cannot stall window expiry. */
public class ViewStatisticsWorkers implements SmartLifecycle {
    private final ViewStatisticsProperties config;private final ViewPublisher publisher;private final ViewKafkaTransport transport;
    private final ViewProjection projection;private final MeterRegistry meters;private final JdbcTemplate jdbc;
    private ScheduledExecutorService publishing,projecting;private volatile boolean running;private volatile double pending,openFailures;
    public ViewStatisticsWorkers(ViewStatisticsProperties config,ViewPublisher publisher,ViewKafkaTransport transport,ViewProjection projection,MeterRegistry meters,JdbcTemplate jdbc){
        this.config=config;this.publisher=publisher;this.transport=transport;this.projection=projection;this.meters=meters;this.jdbc=jdbc;
        meters.gauge("xinkelink.view.receipt.pending",this,w->w.pending);meters.gauge("xinkelink.view.failure.pending",this,w->w.openFailures);
    }
    @Override public synchronized void start(){
        if(running)return;config.validate();if(!config.isPublisherEnabled()&&!config.isProjectionEnabled())return;
        if(publishing!=null&&!publishing.isTerminated()||projecting!=null&&!projecting.isTerminated())throw new IllegalStateException("View workers still stopping");
        running=true;
        if(config.isPublisherEnabled()){publishing=executor("post-view-publisher");publishing.scheduleWithFixedDelay(()->safe(()->publisher.publishBatch(()->running)),0,config.getPollMillis(),TimeUnit.MILLISECONDS);}
        if(config.isProjectionEnabled()){projecting=executor("post-view-projection");projecting.scheduleWithFixedDelay(()->safe(()->{if(running){projection.project();refresh();}}),0,config.getProjectionMillis(),TimeUnit.MILLISECONDS);}
    }
    private static ScheduledExecutorService executor(String name){return Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,name);t.setDaemon(true);return t;});}
    private void safe(Runnable work){if(!running)return;try{work.run();}catch(RuntimeException e){meters.counter("xinkelink.view.worker.failure").increment();}}
    private void refresh(){pending=jdbc.queryForObject("SELECT COUNT(*) FROM xl_view_receipt WHERE state<>'SENT'",Double.class);openFailures=jdbc.queryForObject("SELECT COUNT(*) FROM xl_view_failure WHERE state='OPEN'",Double.class);}
    @Override public synchronized void stop(){running=false;stop(publishing);stop(projecting);transport.close();}
    private static void stop(ExecutorService e){if(e==null)return;e.shutdownNow();try{if(!e.awaitTermination(60,TimeUnit.SECONDS))throw new IllegalStateException("View worker has not stopped");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("View worker stop interrupted");}}
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return 1000;}
}
