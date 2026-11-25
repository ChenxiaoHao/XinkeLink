package cn.xinkelink.notification;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Paired same-JVM writes, full authentication, real committed MySQL and native Broker. Explicit opt-in test name. */
class NotificationMqPerformanceVerification {
    final NotificationMqLiveVerification h=new NotificationMqLiveVerification();
    final List<Map<String,Object>> results=new ArrayList<>();
    @Test void compareAuthenticatedSyncAndMqWithSameBuildAndLoad()throws Exception{
        Files.createDirectories(h.run);h.report.put("runDirectory",h.run.toString());h.report.put("phases",results);h.report.put("passed",false);
        try{
            String url=System.getenv("XINKELINK_DB_URL");assertNotNull(url);assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_[a-zA-Z0-9_]+_test(?:\\?.*)?"));
            h.db=DriverManager.getConnection(url,System.getenv("XINKELINK_DB_USERNAME"),System.getenv("XINKELINK_DB_PASSWORD"));h.fixture();h.startWorker("benchmark",true,true);
            h.report.put("conditions",Map.of("jvm","same JDK21 service JVM / same classes / native broker SYNC_FLUSH","data","fresh natural interactions, identical post/comment text, same author/actor accounts, no images","order","warmup sync/mq; measured sync,mq then mq,sync; each operation concurrency1/4","warmupRequests",160,"measuredRequests",960,"perPhase",60,"sqlProbe","application JDBC execute/commit calls, including authentication; excludes driver internal metadata SQL and external verifier polling","lockProbe","FOR UPDATE call time includes network+execution+wait; InnoDB global lock counters are supplemental","latency","same-JVM JDBC business commit acknowledgement to Inbox/notification commit acknowledgement; synchronous effects share one commit"));
            for(boolean mq:List.of(false,true)){generation(mq);phase(mq,"like",4,40,false);phase(mq,"comment",4,40,false);}
            for(int round=0;round<2;round++)for(boolean mq:round==0?List.of(false,true):List.of(true,false)){
                generation(mq);for(int concurrency:List.of(1,4))for(String operation:List.of("like","comment"))phase(mq,operation,concurrency,60,true);
            }
            generation(true);
            String latest=h.text("SELECT event_id FROM xl_notification_outbox WHERE post_id IN (SELECT id FROM xl_post WHERE author_id=?) ORDER BY created_at DESC LIMIT 1",h.users.getFirst());
            long noticeCount=h.number("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=?",h.users.getFirst());long unread=h.number("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=? AND active=1 AND read_at IS NULL",h.users.getFirst());
            h.send(latest);h.send(latest);h.await(()->{try{return h.json.readTree(Files.readString(h.workerState.resolve("handled-events.json"))).path(latest).asInt()>=3;}catch(Exception e){return false;}},30000,"performanceSpecificNativeDuplicatesConsumed");
            assertEquals(noticeCount,h.number("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=?",h.users.getFirst()));assertEquals(unread,h.number("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=? AND active=1 AND read_at IS NULL",h.users.getFirst()));
            h.report.put("duplicateNoticeAndUnreadUnchanged",true);h.report.put("productionClassHashes",classHashes());h.report.put("passed",true);
        }finally{h.report.put("finishedAt",Instant.now().toString());try{h.stopWorker();}finally{if(h.replayProducer!=null)h.replayProducer.shutdown();for(Process p:h.owned)if(p.isAlive())h.kill(p);if(h.db!=null)h.db.close();Files.writeString(h.run.resolve("performance.json"),h.json.writerWithDefaultPrettyPrinter().writeValueAsString(h.report));Files.writeString(h.evidence.resolve("performance-results.json"),h.json.writerWithDefaultPrettyPrinter().writeValueAsString(h.report));}}
    }
    void generation(boolean mq)throws Exception{Files.writeString(h.workerState.resolve("generation-mode"),mq?"mq":"sync");h.await(()->{try{return h.json.readTree(Files.readString(h.workerState.resolve("generation-mode.json"))).path("mode").asString().equals(mq?"mq":"sync");}catch(Exception e){return false;}},5000,"generation"+mq+results.size());}
    void phase(boolean mq,String operation,int concurrency,int count,boolean measured)throws Exception{
        List<Long> posts=new ArrayList<>();for(int i=0;i<count;i++){String id=UUID.randomUUID().toString();h.sql("INSERT INTO xl_post(author_id,category,content,client_request_id,request_hash) VALUES(?,'campus','MQ matched performance fixture',?,UNHEX(SHA2(?,256)))",h.users.getFirst(),id,id);posts.add(h.number("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?",h.users.getFirst(),id));}
        JsonNode beforeSample=snapshotAfter(Instant.now());JsonNode before=beforeSample.path("sql"),beforeMetrics=beforeSample.path("metrics");long waits=global("Innodb_row_lock_waits"),lockMillis=global("Innodb_row_lock_time");
        List<Double> samples=new ArrayList<>();List<String> keys=new ArrayList<>();int failed=0;long wall=System.nanoTime();
        try(var pool=Executors.newFixedThreadPool(concurrency)){List<Future<Map<String,Object>>> requests=new ArrayList<>();for(long post:posts)requests.add(pool.submit(()->{
            long begin=System.nanoTime();String key;
            if(operation.equals("like")){h.call(18084,"PUT","/api/v1/posts/"+post+"/like",1,null,200);key="post_like:"+post+":"+h.users.get(1);}
            else{var result=h.call(18084,"POST","/api/v1/posts/"+post+"/comments",1,Map.of("content","MQ matched performance comment","clientRequestId",UUID.randomUUID().toString()),201);key="comment:"+result.at("/comment/id").asString();}
            return Map.of("millis",(System.nanoTime()-begin)/1e6,"key",key);
        }));for(var request:requests)try{var result=request.get(30,TimeUnit.SECONDS);samples.add((Double)result.get("millis"));keys.add((String)result.get("key"));}catch(Exception failure){failed++;}}
        double httpWall=(System.nanoTime()-wall)/1e6;assertEquals(0,failed,"authenticated request failures");
        String placeholders=String.join(",",Collections.nCopies(keys.size(),"?"));List<Object> args=new ArrayList<>();args.add(h.users.getFirst());args.addAll(keys);
        h.await(()->h.number("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=? AND event_key IN ("+placeholders+")",args.toArray())==count,30000,"phasePersistence"+results.size()+operation+measured);
        if(mq){String postPlaceholders=String.join(",",Collections.nCopies(posts.size(),"?"));
            h.await(()->h.number("SELECT COUNT(*) FROM xl_notification_outbox WHERE post_id IN ("+postPlaceholders+") AND state='SENT'",posts.toArray())==count,30000,"phasePublishCompleted"+results.size()+operation+measured);
            var ids=posts.stream().map(h::event).toList();h.await(()->{try{var handled=h.json.readTree(Files.readString(h.workerState.resolve("handled-events.json")));return ids.stream().allMatch(id->handled.path(id).asInt()>=1);}catch(Exception e){return false;}},10000,"phaseHandlersCompleted"+results.size()+operation+measured);
        }
        JsonNode afterSample=snapshotAfter(Instant.now());JsonNode after=afterSample.path("sql"),afterMetrics=afterSample.path("metrics");List<Double> latency=new ArrayList<>();
        if(mq){for(long p:posts){String id=h.event(p);long business=after.path("businessCommits").path(id).asLong(),notice=after.path("noticeCommits").path(id).asLong();assertTrue(business>0&&notice>0,"both actual JDBC commits observed");latency.add((notice-business)/1e6);}}else for(int i=0;i<count;i++)latency.add(0.0);
        if(measured){Map<String,Object> result=new LinkedHashMap<>();result.put("mode",mq?"mq":"sync");result.put("operation",operation);result.put("concurrency",concurrency);result.put("samples",samples.size());result.put("failed",failed);result.put("httpMeanMs",mean(samples));result.put("httpP95Ms",p95(samples));result.put("httpWallMs",httpWall);result.put("persistenceDelayMeanMs",mean(latency));result.put("persistenceDelayP95Ms",p95(latency));result.put("httpSamplesMs",samples);result.put("persistenceSamplesMs",latency);
            result.put("jdbcCalls",delta(before.path("calls"),after.path("calls")));result.put("jdbcNanos",delta(before.path("nanos"),after.path("nanos")));result.put("deliveryMetricsDelta",delta(beforeMetrics,afterMetrics));result.put("globalInnoDbLockWaits",global("Innodb_row_lock_waits")-waits);result.put("globalInnoDbLockWaitMs",global("Innodb_row_lock_time")-lockMillis);results.add(result);
            Files.writeString(h.evidence.resolve("performance-progress.json"),h.json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        }
    }
    long global(String name)throws SQLException{try(var s=h.db.prepareStatement("SHOW GLOBAL STATUS LIKE ?")){s.setString(1,name);try(var r=s.executeQuery()){assertTrue(r.next());return Long.parseLong(r.getString(2));}}}
    JsonNode snapshotAfter(Instant timestamp)throws Exception{h.await(()->{try{return Instant.parse(h.json.readTree(Files.readString(h.workerState.resolve("sql-probe.json"))).at("/sql/sampledAt").asString()).isAfter(timestamp);}catch(Exception e){return false;}},5000,"probeSample"+System.nanoTime());return h.json.readTree(Files.readString(h.workerState.resolve("sql-probe.json")));}
    JsonNode metrics()throws Exception{return h.json.readTree(Files.readString(h.workerState.resolve("metrics.json")));}
    Map<String,Double> delta(JsonNode before,JsonNode after){Map<String,Double> result=new TreeMap<>();for(var entry:after.properties())if(entry.getValue().isNumber())result.put(entry.getKey(),entry.getValue().asDouble()-before.path(entry.getKey()).asDouble());return result;}
    double mean(List<Double> values){return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();}
    double p95(List<Double> values){return values.stream().sorted().toList().get((int)Math.ceil(values.size()*.95)-1);}
    Map<String,String> classHashes()throws Exception{Map<String,String> result=new TreeMap<>();for(String name:List.of("NotificationService","mq/InteractionJournal","mq/InboxProcessor","mq/OutboxPublisher","mq/RocketMqTransport")){String resource="/cn/xinkelink/notification/"+name+".class";try(var in=getClass().getResourceAsStream(resource)){result.put(resource,h.sha(Objects.requireNonNull(in).readAllBytes()));}}return result;}
}
