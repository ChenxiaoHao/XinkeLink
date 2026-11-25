package cn.xinkelink.notification.mq;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Workers can drain existing Outbox while generation is switched back to sync. */
public class NotificationDelivery implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(NotificationDelivery.class);
    private final NotificationMqProperties config;private final OutboxPublisher publisher;private final RocketMqTransport transport;
    private final NotificationMessageHandler handler;private final MeterRegistry meters;private final JdbcTemplate jdbc;
    private ScheduledExecutorService executor;private DefaultMQPushConsumer consumer;private volatile boolean running;
    private final Object resources=new Object();
    private final AtomicInteger consumerState=new AtomicInteger(),brokerReachable=new AtomicInteger(-1);
    private final AtomicLong consumerFailureSince=new AtomicLong(),brokerFailureSince=new AtomicLong(),lastSuccess=new AtomicLong();
    private volatile double ready,retry,paused,oldest,failures;private long nextMetrics;
    public NotificationDelivery(NotificationMqProperties config,OutboxPublisher publisher,RocketMqTransport transport,NotificationMessageHandler handler,MeterRegistry meters,JdbcTemplate jdbc){
        this.config=config;this.publisher=publisher;this.transport=transport;this.handler=handler;this.meters=meters;this.jdbc=jdbc;
        meters.gauge("xinkelink.notification.consumer.state",consumerState);
        meters.gauge("xinkelink.notification.broker.reachable",brokerReachable);
        meters.gauge("xinkelink.notification.consumer.last.success.age.seconds",lastSuccess,v->v.get()==0?-1:Math.max(0,(System.currentTimeMillis()-v.get())/1000.0));
        meters.gauge("xinkelink.notification.outbox.pending",this,x->x.ready);
        meters.gauge("xinkelink.notification.outbox.retry",this,x->x.retry);
        meters.gauge("xinkelink.notification.outbox.paused",this,x->x.paused);
        meters.gauge("xinkelink.notification.outbox.oldest.seconds",this,x->x.oldest);
        meters.gauge("xinkelink.notification.failure.pending",this,x->x.failures);
    }
    @Override public synchronized void start(){
        if(running)return;if(executor!=null&&!executor.isTerminated())throw new IllegalStateException("Notification worker still stopping");
        config.validate();if(!config.isPublisherEnabled()&&!config.isConsumerEnabled())return;
        transport.open();running=true;brokerReachable.set(-1);consumerFailureSince.set(0);brokerFailureSince.set(0);executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"notification-delivery");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(this::tick,0,config.getPollMillis(),TimeUnit.MILLISECONDS);
    }
    private void tick(){if(!running)return;
        try {
            if(config.isConsumerEnabled()&&consumer==null)startConsumer();
            if(!running)return;
            if(config.isPublisherEnabled())publisher.publishBatch();
            if(!running)return;
            if(System.nanoTime()>=nextMetrics){probeBroker();refreshMetrics();nextMetrics=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);}
        }catch(RuntimeException e){meters.counter("xinkelink.notification.worker.failure").increment();LOG.warn("Notification worker unavailable; retained delivery state will be retried");}
    }
    private void startConsumer(){consumerState.set(1);
        var next=createConsumer();next.setNamesrvAddr(config.getNamesrvAddr());next.setInstanceName("notice-consumer-"+UUID.randomUUID());
        next.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);next.setMessageModel(MessageModel.CLUSTERING);
        next.setConsumeMessageBatchMaxSize(1);next.setConsumeThreadMin(2);next.setConsumeThreadMax(4);next.setMaxReconsumeTimes(config.getMaxReconsumeTimes());
        next.setVipChannelEnabled(false);next.setPersistConsumerOffsetInterval(1000);
        try {
            next.subscribe(config.getTopic(),"*");
            next.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
                for(var message:messages){var status=handler.handle(message);if(status!=ConsumeConcurrentlyStatus.CONSUME_SUCCESS){retrying();return status;}}
                lastSuccess.set(System.currentTimeMillis());if(running)consumerState.set(3);recovered();
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });long begin=System.nanoTime();next.start();
            synchronized(resources){if(!running){next.shutdown();return;}consumer=next;consumerState.set(2);}
            meters.timer("xinkelink.notification.consumer.startup").record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);
        }catch(Exception e){next.shutdown();if(running)consumerState.set(1);throw new IllegalStateException("Notification consumer start failed",e);}
    }
    private void retrying(){if(running)consumerState.set(4);consumerFailureSince.compareAndSet(0,System.nanoTime());}
    private void recovered(){long begin=consumerFailureSince.getAndSet(0);if(begin!=0)meters.timer("xinkelink.notification.consumer.recovery").record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);}
    /** Bounded native broker RPC outside database transactions; a started client alone is not a reachability proof. */
    protected void probeBroker(){
        DefaultMQPushConsumer current;synchronized(resources){current=consumer;}
        if(current==null)return;
        try{var queues=current.fetchSubscribeMessageQueues(config.getTopic());if(queues.isEmpty())throw new IllegalStateException("No notification queue route");
            var factory=current.getDefaultMQPushConsumerImpl().getmQClientFactory();String address=factory.findBrokerAddressInPublish(queues.iterator().next().getBrokerName());
            if(address==null||factory.getMQClientAPIImpl().getBrokerRuntimeInfo(address,1000)==null)throw new IllegalStateException("Notification broker unavailable");
            brokerReachable.set(1);long begin=brokerFailureSince.getAndSet(0);if(begin!=0)meters.timer("xinkelink.notification.broker.recovery").record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);
            meters.counter("xinkelink.notification.broker.probe","result","OK").increment();
        }catch(Exception e){brokerReachable.set(0);brokerFailureSince.compareAndSet(0,System.nanoTime());meters.counter("xinkelink.notification.broker.probe","result","FAILED").increment();}
    }
    protected DefaultMQPushConsumer createConsumer(){return new DefaultMQPushConsumer(config.getConsumerGroup());}
    private void refreshMetrics(){
        ready=jdbc.queryForObject("SELECT COUNT(*) FROM xl_notification_outbox WHERE state IN ('READY','LEASED')",Double.class);
        retry=jdbc.queryForObject("SELECT COUNT(*) FROM xl_notification_outbox WHERE state='READY' AND attempts>0",Double.class);
        paused=jdbc.queryForObject("SELECT COUNT(*) FROM xl_notification_outbox WHERE state='PAUSED'",Double.class);
        oldest=jdbc.queryForObject("SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND,MIN(created_at),UTC_TIMESTAMP(3))/1000000,0) FROM xl_notification_outbox WHERE state IN ('READY','LEASED')",Double.class);
        failures=jdbc.queryForObject("SELECT COUNT(*) FROM xl_notification_failure WHERE state<>'RESOLVED'",Double.class);
    }
    @Override public synchronized void stop(){DefaultMQPushConsumer current;synchronized(resources){running=false;consumerState.set(0);brokerReachable.set(-1);current=consumer;consumer=null;}
        transport.close();if(executor!=null){executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        if(current!=null)current.shutdown();}
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return 1000;}
}
