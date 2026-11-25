package cn.xinkelink.forum.metrics.cdc;

import cn.xinkelink.forum.metrics.PostMetricsCache;
import com.alibaba.otter.canal.client.*;
import com.alibaba.otter.canal.client.impl.SimpleCanalConnector;
import com.alibaba.otter.canal.protocol.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.RedisConnectionFailureException;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only, independent native consumer JVM. Faults affect only this consumer/connection. */
public final class CdcWorkerProcess {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path directory;
    private static final AtomicLong success = new AtomicLong(), attempted = new AtomicLong(), ack = new AtomicLong();
    private static final AtomicLong batches = new AtomicLong(), affected = new AtomicLong();
    private static final AtomicReference<String> position = new AtomicReference<>("none");
    private static final AtomicLong currentBatch = new AtomicLong(-1);

    public static void main(String[] args) throws Exception {
        directory = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(directory);
        var credentials = JSON.readTree(Files.readString(Path.of(System.getenv("XINKELINK_CDC_PRIVATE_FILE"))));
        String schema = credentials.path("database").asText();
        if (!schema.matches("xinkelink_cache_[a-zA-Z0-9_]+_test")) throw new IllegalStateException("Wrong CDC test schema");
        String user = credentials.path("tcpUsername").asText(), password = credentials.path("tcpPassword").asText();
        String destination = credentials.path("destination").asText("post_metrics_test");
        int port = credentials.path("canalPort").asInt(11111);
        var redisConnections = new LettuceConnectionFactory("127.0.0.1", 16379);
        redisConnections.afterPropertiesSet(); redisConnections.start();
        var redis = new StringRedisTemplate(redisConnections) {
            @Override public <T>T execute(RedisScript<T> script,List<String>keys,Object...arguments) {
                if(script.getScriptAsString().contains("post-metrics-invalidate")&&Files.exists(directory.resolve("redis-fault"))) {
                    marker("redis-blocked",Map.of("time",System.currentTimeMillis(),"position",position.get(),"batch",currentBatch.get()));
                    throw new RedisConnectionFailureException("Controlled dedicated Redis connection unavailable");
                }
                return super.execute(script,keys,arguments);
            }
        };
        var meters = new SimpleMeterRegistry();
        var cache = new PostMetricsCache(redis, true, 20, 10, false, 10000, 2000, meters);
        var settings = new PostMetricsCdcConsumer.Settings(true, "127.0.0.1", port, destination, user, password,
                schema, directory.resolve("checkpoint.properties"), 1024, 100, 3000, 100, 1000);
        var consumer = new PostMetricsCdcConsumer(settings,
                () -> {var connector=new SimpleCanalConnector(new InetSocketAddress("127.0.0.1",port),user,password,destination,3000);connector.setRollbackOnConnect(false);return controlled(connector);},
                new PostMetricsEventMapper(schema), ids -> {
                    attempted.incrementAndGet();
                    cache.invalidateConfirmed(ids);
                    affected.addAndGet(ids.size()); success.incrementAndGet();
                    marker("shared-success", Map.of("time", System.currentTimeMillis(), "position", position.get(), "posts", ids.size()));
                }, meters);
        var sampler = Executors.newSingleThreadScheduledExecutor(r -> {var t=new Thread(r,"live-metrics");t.setDaemon(true);return t;});
        Runnable snapshot = () -> {
            Map<String,Object> values = new LinkedHashMap<>();
            values.put("pid",ProcessHandle.current().pid());
            values.put("state", consumer.state().name()); values.put("sharedSuccess",success.get());
            values.put("sharedAttempted",attempted.get()); values.put("ackReturned",ack.get());
            values.put("batchesFetched",batches.get()); values.put("postsAffected",affected.get()); values.put("position",position.get());
            for (var meter:meters.getMeters()) for(var measurement:meter.measure()) {
                if(meter.getId().getName().startsWith("post.metrics."))
                    values.put(meter.getId().getName()+meter.getId().getTags()+"."+measurement.getStatistic(),measurement.getValue());
            }
            marker("worker-metrics",values);
        };
        sampler.scheduleWithFixedDelay(snapshot,0,100,TimeUnit.MILLISECONDS);
        consumer.start();
        try {
            while(!Files.exists(directory.resolve("stop"))) Thread.sleep(100);
        } finally {
            consumer.stop(); sampler.shutdownNow(); snapshot.run(); redisConnections.destroy(); meters.close();
        }
    }

    private static CanalConnector controlled(CanalConnector delegate) {
        return (CanalConnector)Proxy.newProxyInstance(CanalConnector.class.getClassLoader(),new Class<?>[]{CanalConnector.class},(proxy,method,args)-> {
            String name=method.getName();
            if(name.equals("getWithoutAck") && Files.exists(directory.resolve("network-fault"))) {
                marker("network-blocked",Map.of("time",System.currentTimeMillis()));
                throw new IllegalStateException("Controlled native connection interruption");
            }
            if(name.equals("ack")) {
                marker("before-ack",Map.of("time",System.currentTimeMillis(),"position",position.get(),"batch",args[0]));
                while(Files.exists(directory.resolve("pause-before-ack"))) Thread.sleep(25);
            }
            Object result;
            try {result=method.invoke(delegate,args);} catch(InvocationTargetException failure){throw failure.getCause();}
            if(name.equals("getWithoutAck") && result instanceof Message message && message.getId()>=0) {
                batches.incrementAndGet();
                currentBatch.set(message.getId());
                if(!message.isRaw() && !message.getEntries().isEmpty()) {
                    var h=message.getEntries().getLast().getHeader();
                    position.set(h.getLogfileName()+":"+h.getLogfileOffset());
                }
            }
            if(name.equals("ack")) {
                ack.incrementAndGet();
                try {Files.writeString(directory.resolve("ack-history.jsonl"),JSON.writeValueAsString(Map.of("time",System.currentTimeMillis(),"position",position.get(),"batch",args[0]))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}
                if(Files.exists(directory.resolve("ack-unknown"))) {
                    marker("ack-result-unknown",Map.of("time",System.currentTimeMillis(),"position",position.get()));
                    throw new IllegalStateException("Controlled native ACK result unknown");
                }
            }
            return result;
        });
    }
    static void marker(String name, Map<String,?> value) {
        try {
            Path target=directory.resolve(name+".json"),temporary=directory.resolve(name+".tmp");
            Files.writeString(temporary,JSON.writeValueAsString(value));
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } catch(Exception ignored) { /* Orchestrator treats absent evidence as failure. No raw payload logging. */ }
    }
}
