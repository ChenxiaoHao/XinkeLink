package cn.xinkelink.forum;

import cn.xinkelink.forum.metrics.cdc.*;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.impl.SimpleCanalConnector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit native-CDC idle overhead comparison; inherits the existing HTTP/SQL probes and fixture. */
class PostMetricsCdcPerformanceVerification extends PostMetricsPerformanceBenchmark {
    @Override @Test void compareIdenticalGuestHttpAcrossThreeModes() throws Exception {
        Path evidence=Path.of("D:/CODE/XinKeLink/.local/canal-20261116");
        var credentials=new ObjectMapper().readTree(Files.readString(evidence.resolve("private/cdc.json")));
        String schema=credentials.path("database").asText(), destination=credentials.path("destination").asText();
        String user=credentials.path("tcpUsername").asText(), password=credentials.path("tcpPassword").asText();
        assertTrue(isolatedUrl().contains("/"+schema+"?"));
        var nativeCalls=new AtomicLong();var nativeAcks=new AtomicLong();
        var cdcMeters=new SimpleMeterRegistry();
        var settings=new PostMetricsCdcConsumer.Settings(true,"127.0.0.1",11111,destination,user,password,schema,
                evidence.resolve("state/performance/checkpoint.properties"),512,100,3000,100,1000);
        var consumer=new PostMetricsCdcConsumer(settings,()->{
            var delegate=new SimpleCanalConnector(new InetSocketAddress("127.0.0.1",11111),user,password,destination,3000);
            delegate.setRollbackOnConnect(false);
            return (CanalConnector)Proxy.newProxyInstance(CanalConnector.class.getClassLoader(),new Class[]{CanalConnector.class},(proxy,method,args)->{
                if(method.getName().equals("getWithoutAck"))nativeCalls.incrementAndGet();
                if(method.getName().equals("ack"))nativeAcks.incrementAndGet();
                try{return method.invoke(delegate,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        },new PostMetricsEventMapper(schema),cache::invalidateConfirmed,cdcMeters);
        var results=new ArrayList<Map<String,Object>>();
        long barrier=0;
        try {
            fixture();mode("caffeine_redis");consumer.start();
            String request=UUID.randomUUID().toString();
            Long author=jdbc.queryForObject("SELECT author_id FROM xl_post WHERE id=?",Long.class,posts.getFirst());
            jdbc.update("INSERT INTO xl_post(author_id,category,content,client_request_id,request_hash) VALUES(?,'campus','CDC performance barrier',?,UNHEX(SHA2(?,256)))",author,request,request);
            barrier=jdbc.queryForObject("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?",Long.class,author,request);
            final long barrierId=barrier;long deadline=System.nanoTime()+60_000_000_000L;
            while(!Boolean.TRUE.equals(redis.hasKey("xinkelink:cache:v3:post-metrics:generation:"+barrierId)) && System.nanoTime()<deadline)Thread.sleep(20);
            assertTrue(Boolean.TRUE.equals(redis.hasKey("xinkelink:cache:v3:post-metrics:generation:"+barrierId)),"Actual native CDC must pass the final fixture event before sampling");
            long acknowledged=nativeAcks.get();
            // One later empty native poll establishes that processing and its write-only ACK returned.
            long fetched=nativeCalls.get();while(nativeCalls.get()<=fetched && System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(nativeAcks.get()>=acknowledged);consumer.stop();
            String detail="/api/v1/posts/"+posts.getFirst(), list="/api/v1/posts?sort=latest&size=20&keyword="+marker;
            run(detail,"detail",400,4,false);run(list,"list",400,4,false);
            for(int round=0;round<2;round++) {
                for(boolean enabled:round==0?List.of(false,true):List.of(true,false)) {
                    if(enabled)consumer.start();else consumer.stop();
                    for(String route:List.of("detail","list")) for(int concurrency:List.of(1,4)) {
                        String path=route.equals("detail")?detail:list;clear();run(path,route,80,concurrency,false);
                        long polls=nativeCalls.get(),acks=nativeAcks.get();
                        var row=measure(enabled?"cdc_on":"cdc_off",route,"hot",round,path,120,concurrency,false);
                        row.put("nativeGetWithoutAckCalls",nativeCalls.get()-polls);row.put("nativeAckCalls",nativeAcks.get()-acks);results.add(row);
                    }
                    if(round==0)results.add(measure(enabled?"cdc_on":"cdc_off","list","cold",round,list,40,1,true));
                }
            }
            long failures=results.stream().mapToLong(row->((Number)row.get("failures")).longValue()).sum();
            Files.writeString(evidence.resolve("perf-cdc-http.json"),json(Map.of("conditions",Map.of(
                    "data","Same20posts/10likes10comments, sameJVM/build/client/guest/rootWARN, CaffeineRedis mode throughout",
                    "sampling","800 global warmup +80 per hot cell;2 rotated on/off rounds;120 per hot cell;concurrency1/4;40 cold lists per mode",
                    "scope","Idle designated native consumer, no concurrent writes; HTTP SQL probe excludes Canal server schema metadata SQL and driver-native isolation queries",
                    "redisCalls","Logical script calls and broadcasts from existing cache metrics; Lua internal commands are not network roundtrips",
                    "nativeCalls","Native GET/ACK client calls; protocol heartbeat/auth/subscription and server-internal SQL not enumerated"),"results",results,"failures",failures)));
            assertEquals(0,failures);
        } finally {
            consumer.stop();cdcMeters.close();clear();
            for(long post:posts)jdbc.update("UPDATE xl_post SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE id=?",post);
            if(barrier>0)jdbc.update("UPDATE xl_post SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE id=?",barrier);
        }
    }
}
