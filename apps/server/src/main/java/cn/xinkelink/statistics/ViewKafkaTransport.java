package cn.xinkelink.statistics;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Owned by the single publisher worker. All unsuccessful/unknown sends retain the journal intent. */
public class ViewKafkaTransport implements AutoCloseable {
    private final ViewStatisticsProperties config;
    private Producer<String,byte[]> producer;
    public ViewKafkaTransport(ViewStatisticsProperties config){this.config=config;}
    protected Producer<String,byte[]> createProducer(Map<String,Object> properties){return new KafkaProducer<>(properties);}
    public String send(ViewJournal.Claim c) {
        try {
            if(producer==null)producer=createProducer(properties());
            var future=producer.send(new ProducerRecord<>(config.getTopic(),Long.toString(c.postId()),c.payload().getBytes(StandardCharsets.UTF_8)));
            future.get(config.getSendTimeoutMillis()+500L,TimeUnit.MILLISECONDS);
            return "OK";
        } catch(InterruptedException e){Thread.currentThread().interrupt();return "UNKNOWN";}
        catch(TimeoutException e){return "UNKNOWN";}
        catch(ExecutionException e){return e.getCause() instanceof org.apache.kafka.common.errors.TimeoutException?"UNKNOWN":"FAILED";}
        catch(org.apache.kafka.common.errors.TimeoutException e){return "UNKNOWN";}
        catch(RuntimeException e){return "FAILED";}
    }
    Map<String,Object> properties(){
        Map<String,Object> p=new HashMap<>();p.put("bootstrap.servers",config.getBootstrapServers());
        p.put("key.serializer",StringSerializer.class);p.put("value.serializer",ByteArraySerializer.class);
        p.put("enable.idempotence",true);p.put("acks","all");p.put("max.in.flight.requests.per.connection",5);
        p.put("delivery.timeout.ms",config.getSendTimeoutMillis());p.put("request.timeout.ms",Math.max(250,config.getSendTimeoutMillis()/2));
        p.put("max.block.ms",config.getSendTimeoutMillis());p.put("linger.ms",0);p.put("buffer.memory",8*1024*1024L);
        p.put("max.request.size",4096);p.put("client.id","xl-view-publisher-"+UUID.randomUUID());
        return p;
    }
    @Override public void close(){if(producer!=null){producer.close(Duration.ofSeconds(5));producer=null;}}
}
