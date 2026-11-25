package cn.xinkelink.notification;

import cn.xinkelink.XinkeLinkApplication;
import cn.xinkelink.notification.mq.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.*;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicitly launched test JVM; all failure hooks live in test sources, with no production endpoint or switch. */
public class NotificationMqWorkerProcess {
    static Path state;static String mode;static final JsonMapper JSON=JsonMapper.builder().build();
    static final Map<String,java.util.concurrent.atomic.AtomicInteger> handledEvents=new java.util.concurrent.ConcurrentHashMap<>();
    public static void main(String[] args)throws Exception{
        state=Path.of(args[0]).toAbsolutePath().normalize();mode=args[1];
        if(!state.startsWith(Path.of("D:/CODE/XinKeLink/.local/rocketmq-20261116").toAbsolutePath().normalize())
                &&!state.startsWith(Path.of("D:/CODE/XinKeLink/.local/kafka-20261116/regression-mq").toAbsolutePath().normalize()))throw new IllegalArgumentException("Test state path");
        Files.createDirectories(state);
        SpringApplication app=new SpringApplication(XinkeLinkApplication.class,Hooks.class);
        try(var context=app.run(Arrays.copyOfRange(args,2,args.length))){
            var meters=context.getBean(MeterRegistry.class);var sampler=Executors.newSingleThreadScheduledExecutor();var appliedGeneration=new java.util.concurrent.atomic.AtomicReference<String>("");
            sampler.scheduleWithFixedDelay(()->{try{
                Path publisher=state.resolve("publisher-mode");if(Files.exists(publisher))context.getBean(NotificationMqProperties.class).setPublisherEnabled(Files.readString(publisher).strip().equals("enabled"));
                if(mode.equals("benchmark")){
                    Path generation=state.resolve("generation-mode");if(Files.exists(generation)){
                        String requested=Files.readString(generation).strip();boolean mq=requested.equals("mq");
                        if(!requested.equals(appliedGeneration.get())){
                            var lifecycle=context.getBean(NotificationDelivery.class);lifecycle.stop();
                            var config=context.getBean(NotificationMqProperties.class);config.setEnabled(mq);config.setPublisherEnabled(mq);config.setConsumerEnabled(mq);
                            if(mq)lifecycle.start();appliedGeneration.set(requested);write("generation-mode",Map.of("mode",mq?"mq":"sync"));
                        }
                    }
                }
                Map<String,Object> values=new TreeMap<>();for(var meter:meters.getMeters())if(meter.getId().getName().startsWith("xinkelink.notification"))for(var m:meter.measure())values.put(meter.getId().getName()+meter.getId().getTags()+"."+m.getStatistic(),m.getValue());write("metrics",values);
                if(mode.equals("benchmark")){
                    var handled=new TreeMap<String,Integer>();handledEvents.forEach((id,count)->handled.put(id,count.get()));write("handled-events",handled);
                    write("sql-probe",Map.of("sql",NotificationSqlProbe.snapshot(),"metrics",values,"handled",handled));
                }
            }catch(Exception ignored){}},0,200,TimeUnit.MILLISECONDS);
            try{write("ready",Map.of("pid",ProcessHandle.current().pid(),"mode",mode));while(!Files.exists(state.resolve("stop")))Thread.sleep(100);}
            finally{sampler.shutdownNow();}
        }
    }
    static void write(String name,Object value){try{byte[] body=JSON.writeValueAsBytes(value);Path temp=state.resolve(name+".tmp");try(var file=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){file.write(ByteBuffer.wrap(body));file.force(true);}Files.move(temp,state.resolve(name+".json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){throw new IllegalStateException("Test marker failed",e);}}
    static void halt(String phase,int exit,Object evidence){write(phase,evidence);Runtime.getRuntime().halt(exit);}
    @TestConfiguration(proxyBeanMethods=false)
    static class Hooks {
        @Bean static NotificationSqlProbe notificationSqlProbe(){return new NotificationSqlProbe();}
        @Bean @Primary OutboxPublisher testPublisher(InteractionJournal journal,RocketMqTransport transport,MeterRegistry meters){AtomicBoolean once=new AtomicBoolean();
            return new OutboxPublisher(journal,c->{var receipt=transport.send(c);if(receipt.result().equals("OK")&&once.compareAndSet(false,true)){
                if(mode.equals("sent-halt"))halt("sent-halt",72,Map.of("eventId",c.eventId(),"brokerId",receipt.brokerId()));
                if(mode.equals("unknown")){write("unknown",Map.of("eventId",c.eventId(),"brokerId",receipt.brokerId()));throw new TimeoutException("injected unknown send result after native SEND_OK");}
            }return receipt;},meters){
                @Override public int publishBatch(){if(mode.equals("claim-halt")){var claims=journal.claim();if(!claims.isEmpty())halt("claim-halt",71,Map.of("eventId",claims.getFirst().eventId(),"lease",claims.getFirst().token()));return 0;}return super.publishBatch();}
            };
        }
        @Bean @Primary InboxProcessor testProcessor(InteractionJournal journal,NotificationMapper mapper,NotificationService notices,JsonMapper json,MeterRegistry meters){
            return new InboxProcessor(journal,mapper,notices,json,meters){
                @Override @Transactional(propagation=Propagation.REQUIRES_NEW) public Result process(byte[] body){
                    if(mode.equals("poison")&&Files.exists(state.resolve("poison")))throw new IllegalStateException("injected consumer processing fault");
                    return super.process(body);
                }
            };
        }
        @Bean @Primary NotificationMessageHandler testHandler(InboxProcessor processor,NotificationFailures failures,NotificationMqProperties config,MeterRegistry meters){
            return new NotificationMessageHandler(processor,failures,config,meters){
                @Override public ConsumeConcurrentlyStatus handle(MessageExt message){var result=super.handle(message);
                    if(mode.equals("benchmark")&&result==ConsumeConcurrentlyStatus.CONSUME_SUCCESS)handledEvents.computeIfAbsent(JSON.readTree(message.getBody()).path("eventId").asString(),id->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                    if(mode.equals("consume-halt")&&result==ConsumeConcurrentlyStatus.CONSUME_SUCCESS)halt("consume-halt",73,Map.of("brokerId",message.getMsgId(),"eventId",JSON.readTree(message.getBody()).path("eventId").asString()));return result;}
            };
        }
    }
}
