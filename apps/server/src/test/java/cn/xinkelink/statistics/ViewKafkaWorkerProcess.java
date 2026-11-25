package cn.xinkelink.statistics;

import cn.xinkelink.XinkeLinkApplication;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Launched against BOOT-INF production classes/dependencies; test hooks never enter the delivered JAR. */
public final class ViewKafkaWorkerProcess {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static Path state;static String mode,target;
    static final Map<String,AtomicInteger> handled=new ConcurrentHashMap<>();
    public static void main(String[] args)throws Exception{
        state=Path.of(args[0]).toAbsolutePath().normalize();mode=args[1];target=args[2];
        if(!state.startsWith(Path.of("D:/CODE/XinKeLink/.local/kafka-20261116/live-runs").toAbsolutePath().normalize()))throw new IllegalArgumentException("Isolated worker state only");
        boolean publisher=Boolean.parseBoolean(args[3]),consumer=Boolean.parseBoolean(args[4]),projection=Boolean.parseBoolean(args[5]);Files.createDirectories(state);
        SpringApplication app=new SpringApplication(XinkeLinkApplication.class);
        app.addInitializers(context->context.getBeanFactory().addBeanPostProcessor(new ViewStatisticsSqlProbe()));
        try(var context=app.run(Arrays.copyOfRange(args,6,args.length))){
            ViewStatisticsProperties config=context.getBean(ViewStatisticsProperties.class);
            config.setEnabled(true);config.setPublisherEnabled(publisher);config.setConsumerEnabled(consumer);config.setProjectionEnabled(projection);
            MeterRegistry meters=context.getBean(MeterRegistry.class);JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
            ViewProcessor actual=context.getBean(ViewProcessor.class);
            ViewProcessor guarded=new ViewProcessor(jdbc,JSON,Clock.systemUTC()){
                @Override public Result process(byte[] bytes){
                    if(mode.equals("poison")&&Files.exists(state.resolve("poison"))&&JSON.readTree(bytes).path("eventId").asString().equals(target))throw new IllegalStateException("Controlled processor failure");
                    return actual.process(bytes);
                }
            };
            ViewConsumer controlledConsumer=new ViewConsumer(config,guarded,context.getBean(ViewFailures.class),meters){
                @Override protected void afterProcessed(ConsumerRecord<String,byte[]> record,ViewProcessor.Result result){String id=JSON.readTree(record.value()).path("eventId").asString();handled.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();
                    if(mode.equals("consume-halt")&&id.equals(target))halt("consume-halt",82,Map.of("eventId",id,"partition",record.partition(),"offset",record.offset(),"transactionResult",result.name()));
                }
            };
            AtomicBoolean unknown=new AtomicBoolean();ViewKafkaTransport transport=new ViewKafkaTransport(config){
                @Override public String send(ViewJournal.Claim claim){String outcome=super.send(claim);
                    if(mode.equals("unknown")&&claim.eventId().equals(target)&&outcome.equals("OK")&&unknown.compareAndSet(false,true)){write("unknown",Map.of("eventId",claim.eventId(),"injection","after real Kafka acknowledgement"));return "UNKNOWN";}
                    return outcome;
                }
            };
            ViewPublisher controlledPublisher=new ViewPublisher(context.getBean(ViewJournal.class),transport,meters){
                @Override protected void afterSend(ViewJournal.Claim claim,String result){if(mode.equals("sent-halt")&&claim.eventId().equals(target)&&result.equals("OK"))halt("sent-halt",81,Map.of("eventId",claim.eventId(),"kafkaAcknowledged",true));}
            };
            ViewStatisticsWorkers controlledWorkers=new ViewStatisticsWorkers(config,controlledPublisher,transport,context.getBean(ViewProjection.class),meters,jdbc);
            ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"view-verification-sampler");t.setDaemon(true);return t;});
            try{
                controlledWorkers.start();controlledConsumer.start();
                sampler.scheduleWithFixedDelay(()->{try{
                    Path collection=state.resolve("collection-mode");if(mode.equals("benchmark")&&Files.exists(collection)){String requested=Files.readString(collection).strip();config.setEnabled(requested.equals("on"));write("collection-mode",Map.of("mode",requested));}
                    Map<String,Object> values=new TreeMap<>();for(var meter:meters.getMeters())if(meter.getId().getName().startsWith("xinkelink.view")||meter.getId().getName().startsWith("view.projection"))for(var measurement:meter.measure())values.put(meter.getId().getName()+meter.getId().getTags()+"."+measurement.getStatistic(),measurement.getValue());
                    var events=new TreeMap<String,Integer>();handled.forEach((id,n)->events.put(id,n.get()));write("metrics",values);write("handled-events",events);write("sql-probe",ViewStatisticsSqlProbe.snapshot());
                }catch(Exception ignored){}},0,100,TimeUnit.MILLISECONDS);
                write("ready",Map.of("pid",ProcessHandle.current().pid(),"mode",mode,"productionLocation",ViewConsumer.class.getProtectionDomain().getCodeSource().getLocation().toString()));
                while(!Files.exists(state.resolve("stop")))Thread.sleep(100);
            }finally{sampler.shutdownNow();if(!sampler.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Sampler did not stop");controlledConsumer.stop();controlledWorkers.stop();}
        }
    }
    static void write(String name,Object value){try{Path temporary=state.resolve(name+".tmp");byte[] bytes=JSON.writeValueAsBytes(value);try(var file=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){file.write(ByteBuffer.wrap(bytes));file.force(true);}Files.move(temporary,state.resolve(name+".json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){throw new IllegalStateException("Verification marker failed");}}
    static void halt(String name,int code,Object value){write(name,value);Runtime.getRuntime().halt(code);}
}
