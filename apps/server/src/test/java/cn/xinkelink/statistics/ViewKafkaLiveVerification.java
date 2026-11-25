package cn.xinkelink.statistics;

import cn.xinkelink.auth.token.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-broker test. Uses two production packaged JVMs, then test-only controlled crash workers. */
class ViewKafkaLiveVerification {
    final Path root=Path.of("D:/CODE/XinKeLink"),evidence=root.resolve(".local/kafka-20261116"),run=evidence.resolve("live-runs/"+UUID.randomUUID());
    final Path jar=Path.of(System.getProperty("kafka.live.jar",root.resolve("apps/server/target/kafka/xinkelink-server-0.1.0-SNAPSHOT.jar").toString()));
    final JsonMapper json=JsonMapper.builder().build();final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final Map<String,Object> report=new LinkedHashMap<>(),checks=new LinkedHashMap<>();final List<Process> owned=new ArrayList<>();
    final List<Long> users=new ArrayList<>(),posts=new ArrayList<>();final List<String> tokens=new ArrayList<>(),credentials=new ArrayList<>();
    final Set<String> accepted=new LinkedHashSet<>();final List<Double> acceptedHttp=new ArrayList<>(),aggregateDelay=new ArrayList<>();
    String stage="setup",topic,group,namespace;Connection db;Admin admin;Process a,b;int workerNumber;final List<Worker> workers=new ArrayList<>();
    LettuceConnectionFactory redisFactory;StringRedisTemplate redis;
    record Worker(Process process,Path state,int port){}

    @Test void realKafkaPipelineSurvivesReplaysCrashesRebalancesAndProjectionLoss()throws Exception{
        setup();report.put("checks",checks);report.put("passed",false);
        try{
            a=service(18086,false,false,false);b=service(18087,true,true,true);check(a.pid()!=b.pid(),"twoIndependentProductionServiceJvms");fixture();
            for(int i=0;i<13;i++)credentials.add(call(18086,"POST","/api/v1/statistics/anonymous-credential",-1,Map.of(),200).path("credential").asString());
            stage="public-statistics";long old=post(true);sql("UPDATE xl_post SET created_at=TIMESTAMPADD(YEAR,-2,UTC_TIMESTAMP(3)) WHERE id=?",old);
            for(int i=0;i<60;i++)directPost("新帖列表范围夹具",false);
            String first=receive(18086,old,-1,credentials.getFirst(),null);
            receive(18086,old,-1,credentials.getFirst(),first); // Same ID is a retry, not another PV.
            receive(18086,old,-1,credentials.getFirst(),null); // New page: another PV, same UV.
            for(int i=1;i<credentials.size();i++)receive(18086,old,-1,credentials.get(i),null);
            for(int i=0;i<3;i++)receive(18086,old,i,null,null);
            await(()->pv(old)==17&&uv(old)==16,30000,"sameVisitorPvAndVerifiedVisitorUv");
            long visibleStart=System.nanoTime();await(()->rankContains(18087,old),15000,"oldPostBeyondFirst50VisibleFromOtherJvm");
            report.put("firstAggregateToObservedRankingMillis",(System.nanoTime()-visibleStart)/1e6);
            var ranking=ranking(18087);check(ranking.at("/window/days").asInt()==7&&ranking.at("/window/zone").asString().equals("Asia/Shanghai"),"calendarWindowDeclared");check(!ranking.path("updatedAt").isNull(),"rankingHasUpdatedAt");
            for(var item:ranking.path("items"))if(item.at("/post/id").asLong()==old)check(item.at("/post/author/id").isNull()&&item.at("/post/author/isAnonymous").asBoolean(),"anonymousProjectionSafe");
            call(18086,"POST","/api/v1/statistics/post-views",-1,Map.of("version",1,"eventId",UUID.randomUUID().toString(),"postId",""+old,"anonymousCredential","forged"),400);
            call(18086,"POST","/api/v1/statistics/post-views",0,Map.of("version",1,"eventId",UUID.randomUUID().toString(),"postId",""+old,"userId","999"),400);
            call(18086,"POST","/api/v1/statistics/post-views",0,Map.of("version",1,"eventId",UUID.randomUUID().toString(),"postId",""+old,"occurredAt","2000-01-01T00:00:00Z"),400);
            call(18086,"POST","/api/v1/statistics/post-views",0,Map.of("version",1,"eventId",UUID.randomUUID().toString(),"postId",""+old,"padding","x".repeat(2050)),400);
            check(pv(old)==17&&uv(old)==16,"invalidRequestsDoNotMutateCounts");

            stage="visibility-top5";List<Long> rankedOwn=new ArrayList<>();rankedOwn.add(old);
            for(int i=0;i<8;i++){long p=post(true);rankedOwn.add(p);for(int visitor=0;visitor<12;visitor++)receive(18086,p,-1,credentials.get(visitor),null);}
            await(()->rankedOwn.stream().allMatch(p->pv(p)>0),30000,"visibilityCandidatesAggregated");
            await(()->rankingMatchesDatabase(18087),15000,"rankingEqualsExactDatabaseOrder");
            for(int i=0;i<6;i++)sql("UPDATE xl_post SET status='hidden' WHERE id=?",rankedOwn.get(i));
            var filtered=ranking(18087);check(filtered.path("items").size()==5,"hiddenTop6AreReplacedByVisibleTop5");
            check(rankIds(filtered).stream().noneMatch(id->rankedOwn.subList(0,6).contains(id)),"hiddenIdsNeverLeakFromRedis");
            call(18086,"POST","/api/v1/statistics/post-views",0,Map.of("version",1,"eventId",UUID.randomUUID().toString(),"postId",""+old),404);
            for(int i=0;i<6;i++)sql("UPDATE xl_post SET status='visible' WHERE id=?",rankedOwn.get(i));check(rankContains(18087,old),"restorationVisibleWithoutNewEvent");
            sql("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",old);check(!rankContains(18087,old),"deletedPostFilteredImmediately");sql("UPDATE xl_post SET deleted_at=NULL,review_state='pending' WHERE id=?",old);check(!rankContains(18087,old),"reviewPendingFilteredImmediately");sql("UPDATE xl_post SET review_state='normal' WHERE id=?",old);
            check(rankContains(18087,old),"reviewRestorationVisibleImmediately");
            var beforeRebuild=rankIds(ranking(18087));long rebuilding=System.nanoTime();redis.delete(List.of("xl:view:{"+namespace+"}:ranking","xl:view:{"+namespace+"}:generation"));
            await(()->rankIdsQuiet(18087).equals(beforeRebuild),10000,"namespaceLossAutomaticallyRebuilt");report.put("redisRebuildMillis",(System.nanoTime()-rebuilding)/1e6);

            stage="cross-producer-replay";send(topic,old,body(first));RecordMetadata duplicate;try(var independent=producer()){duplicate=independent.send(new ProducerRecord<>(topic,""+old,body(first))).get(10,TimeUnit.SECONDS);}
            await(()->committed(group,new TopicPartition(topic,duplicate.partition()))>duplicate.offset(),15000,"independentProducerReplayActuallyCommitted");
            check(eventCount(first)==1,"eventUniqueAcrossIndependentProducers");check(pv(old)==17&&uv(old)==16,"nativeDuplicateDoesNotInflateCounts");
            kill(b);await(()->!portOpen(18087),10000,"priorServicePortReleased");b=service(18087,false,false,true); // Projection reader remains available while controlled workers own ingestion.

            stage="send-confirmed-before-journal-crash";long sentPost=post(false);String sent=receive(18086,sentPost,1,null,null);
            Worker sentWorker=worker("sent-halt",sent,true,false,false,18088,null,null);crash(sentWorker,"sent-halt",81);
            check(text("SELECT state FROM xl_view_receipt WHERE event_id=?",sent).equals("LEASED")&&eventCount(sent)==0,"nativeAckCrashRetainsUnfinishedReceipt");
            Worker recovered=worker("normal","",true,true,false,18088,null,null);await(()->eventCount(sent)==1&&text("SELECT state FROM xl_view_receipt WHERE event_id=?",sent).equals("SENT"),30000,"producerRestartReplaysSameIntent");stop(recovered);

            stage="unknown-send-result";long unknownPost=post(false);String unknown=receive(18086,unknownPost,1,null,null);Worker unknownWorker=worker("unknown",unknown,true,true,false,18088,null,null);
            await(()->Files.exists(unknownWorker.state.resolve("unknown.json")),15000,"unknownInjectedAfterRealKafkaAck");
            await(()->eventCount(unknown)==1&&number("SELECT attempts FROM xl_view_receipt WHERE event_id=?",unknown)>=2&&text("SELECT state FROM xl_view_receipt WHERE event_id=?",unknown).equals("SENT"),30000,"unknownResultRetriedWithoutDuplicateEffect");
            check(pv(unknownPost)==1&&uv(unknownPost)==1,"unknownResultExactCounts");stop(unknownWorker);

            stage="mysql-commit-before-offset-crash";long committedPost=post(false);String committed=receive(18086,committedPost,2,null,null);Worker committedWorker=worker("consume-halt",committed,true,true,false,18088,null,null);crash(committedWorker,"consume-halt",82);
            check(eventCount(committed)==1&&pv(committedPost)==1,"mysqlCommitPrecedesActualProcessHalt");
            Worker replayed=worker("normal","",true,true,false,18088,null,null);await(()->handled(replayed,committed)>0,30000,"uncommittedKafkaOffsetActuallyReplays");check(eventCount(committed)==1&&pv(committedPost)==1,"offsetCrashReplayDoesNotRepeatEffects");stop(replayed);

            stage="real-broker-stop-start";Worker outage=worker("normal","",true,true,false,18088,null,null);broker("Stop");check(!portOpen(39092),"onlyTaskBrokerActuallyStopped");long outagePost=post(false);String outageId=receive(18086,outagePost,1,null,null);
            check(eventCount(outageId)==0&&number("SELECT COUNT(*) FROM xl_view_receipt WHERE event_id=?",outageId)==1,"brokerDownReceiptStillDurablyAccepted");
            long brokerStart=System.nanoTime();broker("Start");await(()->eventCount(outageId)==1,90000,"brokerRestartAutomaticallyRecovers");report.put("brokerRestartRecoveryMillis",(System.nanoTime()-brokerStart)/1e6);stop(outage);

            stage="consumer-backlog";Worker publishing=worker("normal","",true,false,false,18088,null,null);long backlogPost=post(false);List<String> backlog=new ArrayList<>();for(int i=0;i<8;i++)backlog.add(receive(18086,backlogPost,1,null,null));
            await(()->backlog.stream().allMatch(id->text("SELECT state FROM xl_view_receipt WHERE event_id=?",id).equals("SENT")),30000,"publisherFillsKafkaWhileConsumerStopped");check(pv(backlogPost)==0,"stoppedConsumerHasActualBacklog");stop(publishing);
            long recovery=System.nanoTime();Worker draining=worker("normal","",false,true,false,18088,null,null);long ready=System.nanoTime();await(()->pv(backlogPost)==8,30000,"consumerRestartDrainsAllAcceptedBacklog");report.put("consumerBacklog",8);report.put("consumerRecoveryIncludingJvmMillis",(System.nanoTime()-recovery)/1e6);report.put("consumerRecoveryAfterReadyMillis",(System.nanoTime()-ready)/1e6);check(uv(backlogPost)==1,"backlogRepeatedVisitorStillOneUv");stop(draining);

            rebalance();repairableFailure();illegalProtocol();
            stage="reconciliation";await(()->accepted.stream().allMatch(id->eventCount(id)==1),30000,"allConfirmedUniqueReceiptsHaveOnePersistentEffect");
            long unique=0,pvTotal=0;for(String id:accepted){unique+=eventCount(id);aggregateDelay.add(number("SELECT TIMESTAMPDIFF(MICROSECOND,r.occurred_at,e.processed_at) FROM xl_view_receipt r JOIN xl_view_event e ON e.event_id=r.event_id WHERE r.event_id=?",id)/1000.0);}
            for(long p:posts)pvTotal+=pv(p);check(unique==accepted.size()&&pvTotal==accepted.size(),"acceptedUniqueEventPvReconciliation");
            report.put("acceptedUnique",accepted.size());report.put("persistentUnique",unique);report.put("ownedPostPv",pvTotal);report.put("receiveHttp",summary(acceptedHttp));report.put("receiveToAggregate",summary(aggregateDelay));
            report.put("faultBoundaries",List.of("Broker normal TERM/Start is real process outage","producer UNKNOWN is injected after real acknowledgement","consumer Runtime.halt is actual post-MySQL/pre-offset JVM exit","poison processor is controlled adapter failure; retry and Kafka offsets are real","Redis namespace deletion affects only this test namespace; no FLUSH"));
            report.put("passed",true);
        }finally{finish("live-results.json");}
    }

    void setup()throws Exception{
        Files.createDirectories(run);report.put("runDirectory",run.toString());report.put("startedAt",Instant.now().toString());
        String url=System.getenv("XINKELINK_DB_URL");assertNotNull(url);assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_20261114_[a-f0-9]{8}_test(?:\\?.*)?"),"Named isolated schema required");
        assertTrue(Files.isRegularFile(jar));report.put("jarSha256",sha(Files.readAllBytes(jar)));
        topic=System.getenv("XINKELINK_VIEW_TOPIC");group=System.getenv("XINKELINK_VIEW_CONSUMER_GROUP")+"_"+run.getFileName().toString().substring(0,8);namespace=System.getenv("XINKELINK_VIEW_NAMESPACE")+"_"+run.getFileName().toString().substring(0,8);
        assertTrue(topic!=null&&topic.matches("xl_post_views_20261116_[a-f0-9]{8}_test_v1"));assertEquals("127.0.0.1:39092",System.getenv("XINKELINK_VIEW_BOOTSTRAP_SERVERS"));report.put("topic",topic);report.put("consumerGroup",group);report.put("namespace",namespace);
        db=DriverManager.getConnection(url,System.getenv("XINKELINK_DB_USERNAME"),System.getenv("XINKELINK_DB_PASSWORD"));assertEquals(41,number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND LEFT(table_name,3)='xl_'"));
        admin=Admin.create(Map.of("bootstrap.servers","127.0.0.1:39092","default.api.timeout.ms",5000,"request.timeout.ms",3000));assertEquals(3,admin.describeTopics(List.of(topic)).allTopicNames().get(10,TimeUnit.SECONDS).get(topic).partitions().size());
        redisFactory=new LettuceConnectionFactory("127.0.0.1",16379);redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
    }
    void fixture()throws Exception{var jwt=new JwtTokenService(new TokenProperties(System.getenv("XINKELINK_JWT_SECRET_BASE64"),"xinkelink"),Clock.systemUTC());for(int i=0;i<3;i++){String open=UUID.randomUUID().toString();sql("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('kafka-live-it',?,'浏览统计专项用户')",open);long id=number("SELECT id FROM xl_user WHERE wechat_app_id='kafka-live-it' AND wechat_open_id=?",open);users.add(id);sql("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(3)))",id);long session=number("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",id);tokens.add(jwt.issue("user",id,session,Instant.now().plusSeconds(3600)));}}
    void rebalance()throws Exception{
        stage="two-consumer-rebalance";Worker first=worker("normal","",true,true,false,18088,null,null);Worker second=worker("normal","",true,true,false,18089,null,null);
        await(()->members(group)==2&&assigned(group)==3&&assignedMembers(group)==2,30000,"twoActualGroupMembersShareThreePartitions");
        List<String> events=new ArrayList<>();for(int i=0;i<24;i++)events.add(receive(18086,post(false),1,null,null));
        await(()->events.stream().allMatch(id->eventCount(id)==1),30000,"twoConsumersPersistConcurrentEventsOnce");
        await(()->events.stream().anyMatch(id->handled(first,id)>0)&&events.stream().anyMatch(id->handled(second,id)>0),10000,"bothConsumerJvmsActuallyProcessRecords");
        kill(first.process);await(()->members(group)==1&&assigned(group)==3,30000,"actualConsumerExitReassignsAllPartitions");
        List<String> after=new ArrayList<>();for(int i=0;i<8;i++)after.add(receive(18086,post(false),2,null,null));await(()->after.stream().allMatch(id->eventCount(id)==1),30000,"remainingConsumerRecoversAfterRebalance");stop(second);report.put("parallelEvents",events.size()+after.size());
    }
    void repairableFailure()throws Exception{
        stage="durable-failure-repair";long p=post(false);String failed=receive(18086,p,1,null,null);String later=receive(18086,p,2,null,null);
        Worker poison=worker("poison",failed,true,true,false,18088,null,null);
        await(()->number("SELECT COUNT(*) FROM xl_view_failure WHERE topic=? AND state='OPEN' AND attempts>=2",topic)>0,30000,"controlledProcessingFailureIsDurable");
        check(eventCount(failed)==0&&eventCount(later)==0,"samePartitionHigherRecordCannotPassFailure");
        Files.delete(poison.state.resolve("poison"));await(()->eventCount(failed)==1&&eventCount(later)==1,30000,"repairReplaysOriginalRecordInPlace");
        check(number("SELECT COUNT(*) FROM xl_view_failure WHERE topic=? AND state='OPEN'",topic)==0,"repairedFailuresResolvePersistently");check(pv(p)==2&&uv(p)==2,"repairHasExactEffects");stop(poison);
    }
    void illegalProtocol()throws Exception{
        stage="illegal-protocol";String badTopic=topic+"_poison_"+run.getFileName().toString().substring(0,8),badGroup=group+"_poison";
        admin.createTopics(List.of(new NewTopic(badTopic,1,(short)1).configs(Map.of(TopicConfig.RETENTION_MS_CONFIG,"604800000")))).all().get(10,TimeUnit.SECONDS);
        send(badTopic,posts.getFirst(),"{\"version\":999}".getBytes(StandardCharsets.UTF_8));send(badTopic,posts.getFirst(),body(accepted.iterator().next()));
        Worker invalid=worker("normal","",false,true,false,18088,badTopic,badGroup);
        await(()->number("SELECT COUNT(*) FROM xl_view_failure WHERE topic=? AND record_offset=0 AND reason='INVALID_PROTOCOL' AND attempts>=2",badTopic)==1,30000,"illegalProtocolPersistentlyBlocksPartition");
        check(committed(badGroup,new TopicPartition(badTopic,0))<=0,"illegalProtocolNeverAcknowledged");
        admin.deleteRecords(Map.of(new TopicPartition(badTopic,0),RecordsToDelete.beforeOffset(1))).all().get(10,TimeUnit.SECONDS);
        Thread.sleep(1000);check(committed(badGroup,new TopicPartition(badTopic,0))<=0,"retentionLossCannotSilentlyAdvancePastFailedOffset");
        stop(invalid);report.put("illegalProtocolTopicRetained",badTopic);report.put("illegalProtocolFailureRetained",true);
    }
    String receive(int port,long post,int user,String credential,String retry)throws Exception{String id=retry==null?UUID.randomUUID().toString():retry;Map<String,Object> body=new LinkedHashMap<>();body.put("version",1);body.put("eventId",id);body.put("postId",""+post);if(credential!=null)body.put("anonymousCredential",credential);long begin=System.nanoTime();var result=call(port,"POST","/api/v1/statistics/post-views",user,body,202);acceptedHttp.add((System.nanoTime()-begin)/1e6);assertTrue(result.path("accepted").asBoolean());assertEquals(id,result.path("eventId").asString());accepted.add(id);return id;}
    long post(boolean anonymous)throws Exception{long id=call(18086,"POST","/api/v1/posts",0,Map.of("content","Kafka隔离浏览验收","category","campus","imageIds",List.of(),"anonymous",anonymous,"clientRequestId",UUID.randomUUID().toString()),201).path("id").asLong();posts.add(id);return id;}
    long directPost(String content,boolean anonymous)throws SQLException{String id=UUID.randomUUID().toString();sql("INSERT INTO xl_post(author_id,category,content,is_anonymous,anonymous_label,client_request_id,request_hash) VALUES(?,'campus',?,?,?, ?,UNHEX(SHA2(?,256)))",users.getFirst(),content,anonymous,anonymous?"匿名测试":null,id,id);long p=number("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?",users.getFirst(),id);posts.add(p);return p;}
    long eventCount(String id){return number("SELECT COUNT(*) FROM xl_view_event WHERE event_id=?",id);}
    long pv(long p){return number("SELECT COALESCE(SUM(pv),0) FROM xl_view_daily WHERE post_id=?",p);}
    long uv(long p){return number("SELECT COUNT(DISTINCT visitor_key) FROM xl_view_visitor_day WHERE post_id=?",p);}
    byte[] body(String id){return text("SELECT payload FROM xl_view_receipt WHERE event_id=?",id).getBytes(StandardCharsets.UTF_8);}
    JsonNode call(int port,String method,String path,int user,Object body,int status)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));if(user>=0)builder.header("Authorization","Bearer "+tokens.get(user));if(body!=null)builder.header("Content-Type","application/json");var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(status,response.statusCode(),"HTTP "+method+" "+path+" at "+stage);return json.readTree(response.body()).path("data");}
    JsonNode ranking(int port)throws Exception{return call(port,"GET","/api/v1/posts/view-ranking",-1,null,200);}
    List<Long> rankIds(JsonNode response){List<Long> ids=new ArrayList<>();for(var item:response.path("items"))ids.add(item.at("/post/id").asLong());return ids;}
    List<Long> rankIdsQuiet(int port){try{return rankIds(ranking(port));}catch(Exception e){return List.of();}}
    boolean rankContains(int port,long id){return rankIdsQuiet(port).contains(id);}
    boolean rankingMatchesDatabase(int port){try{List<Long> expected=new ArrayList<>();try(var statement=db.prepareStatement("""
        SELECT p.id FROM xl_post p JOIN
          (SELECT post_id,COUNT(DISTINCT visitor_key) uv FROM xl_view_visitor_day WHERE view_date>=DATE(TIMESTAMPADD(HOUR,8,UTC_TIMESTAMP(3)))-INTERVAL 6 DAY AND view_date<=DATE(TIMESTAMPADD(HOUR,8,UTC_TIMESTAMP(3))) GROUP BY post_id) v ON v.post_id=p.id
        WHERE p.deleted_at IS NULL AND p.status='visible' AND p.review_state='normal' ORDER BY v.uv DESC,p.id ASC LIMIT 5
        """);var rows=statement.executeQuery()){while(rows.next())expected.add(rows.getLong(1));}return rankIds(ranking(port)).equals(expected);}catch(Exception e){return false;}}
    List<String> arguments(int port,boolean publisher,boolean consumer,boolean projection,String overrideTopic,String overrideGroup){return List.of("--spring.profiles.active=db,auth","--server.address=127.0.0.1","--server.port="+port,"--logging.level.root=WARN","--xinkelink.notification.mq.enabled=false","--xinkelink.notification.mq.publisher-enabled=false","--xinkelink.notification.mq.consumer-enabled=false","--xinkelink.cache.post-metrics.cdc.enabled=false","--xinkelink.statistics.enabled=true","--xinkelink.statistics.publisher-enabled="+publisher,"--xinkelink.statistics.consumer-enabled="+consumer,"--xinkelink.statistics.projection-enabled="+projection,"--xinkelink.statistics.bootstrap-servers=127.0.0.1:39092","--xinkelink.statistics.topic="+(overrideTopic==null?topic:overrideTopic),"--xinkelink.statistics.consumer-group="+(overrideGroup==null?group:overrideGroup),"--xinkelink.statistics.namespace="+namespace,"--xinkelink.statistics.send-timeout-millis=1000","--xinkelink.statistics.lease-millis=5000","--xinkelink.statistics.poll-millis=100","--xinkelink.statistics.projection-millis=300");}
    List<String> jvm(){return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xms64m","-Xmx256m","-XX:MaxDirectMemorySize=96m","-XX:ActiveProcessorCount=2"));}
    Process service(int port,boolean publisher,boolean consumer,boolean projection)throws Exception{assertFalse(portOpen(port),"Owned test port must be free");List<String> command=jvm();command.addAll(List.of("-jar",jar.toString()));command.addAll(arguments(port,publisher,consumer,projection,null,null));Process process=launch(command,"service-"+port+"-"+owned.size());await(()->process.isAlive()&&healthy(port),60000,"serviceHealthy"+port+"_"+owned.size());return process;}
    Worker worker(String mode,String target,boolean publisher,boolean consumer,boolean projection,int port,String overrideTopic,String overrideGroup)throws Exception{assertFalse(portOpen(port),"Owned worker port must be free");Path state=run.resolve("worker-"+(++workerNumber)+"-"+mode);Files.createDirectories(state);if(mode.equals("poison"))Files.writeString(state.resolve("poison"),"controlled processor fault");List<String> command=jvm();command.addAll(List.of("-Dloader.path="+root.resolve("apps/server/target/kafka/test-classes"),"-Dloader.main="+ViewKafkaWorkerProcess.class.getName(),"-cp",jar.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher",state.toString(),mode,target,""+publisher,""+consumer,""+projection));command.addAll(arguments(port,false,false,false,overrideTopic,overrideGroup));var process=launch(command,"worker-"+workerNumber+"-"+mode);Worker result=new Worker(process,state,port);workers.add(result);if(!mode.endsWith("halt"))await(()->process.isAlive()&&Files.exists(state.resolve("ready.json")),60000,"workerReady"+workerNumber);return result;}
    Process launch(List<String> command,String name)throws Exception{var process=new ProcessBuilder(command).directory(root.resolve("apps/server").toFile()).redirectOutput(run.resolve(name+".stdout.log").toFile()).redirectError(run.resolve(name+".stderr.log").toFile()).start();owned.add(process);return process;}
    boolean healthy(int port){try{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}catch(Exception e){return false;}}
    void stop(Worker worker)throws Exception{if(!worker.process.isAlive())return;Files.writeString(worker.state.resolve("stop"),"stop");if(!worker.process.waitFor(30,TimeUnit.SECONDS)){kill(worker.process);throw new IllegalStateException("Controlled worker normal stop timed out");}assertEquals(0,worker.process.exitValue(),"Worker normal exit");await(()->!portOpen(worker.port),10000,"workerPortReleased"+workerNumber);}
    void crash(Worker worker,String marker,int code)throws Exception{await(()->Files.exists(worker.state.resolve(marker+".json")),60000,marker+"ActualBoundaryMarker");assertTrue(worker.process.waitFor(10,TimeUnit.SECONDS));check(worker.process.exitValue()==code,marker+"ActualJvmExit");await(()->!portOpen(worker.port),10000,marker+"PortReleased");}
    int handled(Worker worker,String id){try{return json.readTree(Files.readString(worker.state.resolve("handled-events.json"))).path(id).asInt();}catch(Exception e){return 0;}}
    int members(String targetGroup){try{return admin.describeConsumerGroups(List.of(targetGroup)).describedGroups().get(targetGroup).get(5,TimeUnit.SECONDS).members().size();}catch(Exception e){return 0;}}
    int assigned(String targetGroup){try{return admin.describeConsumerGroups(List.of(targetGroup)).describedGroups().get(targetGroup).get(5,TimeUnit.SECONDS).members().stream().mapToInt(m->m.assignment().topicPartitions().size()).sum();}catch(Exception e){return 0;}}
    long assignedMembers(String targetGroup){try{return admin.describeConsumerGroups(List.of(targetGroup)).describedGroups().get(targetGroup).get(5,TimeUnit.SECONDS).members().stream().filter(m->!m.assignment().topicPartitions().isEmpty()).count();}catch(Exception e){return 0;}}
    long committed(String targetGroup,TopicPartition partition){try{var value=admin.listConsumerGroupOffsets(targetGroup).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(partition);return value==null?-1:value.offset();}catch(Exception e){throw new IllegalStateException("Kafka offset inspection failed");}}
    KafkaProducer<String,byte[]> producer(){return new KafkaProducer<>(Map.of("bootstrap.servers","127.0.0.1:39092","key.serializer",StringSerializer.class,"value.serializer",ByteArraySerializer.class,"enable.idempotence",true,"acks","all","delivery.timeout.ms",10000,"request.timeout.ms",5000,"max.block.ms",5000));}
    void send(String targetTopic,long post,byte[] bytes)throws Exception{try(var producer=producer()){producer.send(new ProducerRecord<>(targetTopic,""+post,bytes)).get(15,TimeUnit.SECONDS);}}
    void broker(String action)throws Exception{var process=new ProcessBuilder(System.getenv("XINKELINK_TEST_PWSH"),"-NoProfile","-File",evidence.resolve("kafka-server.ps1").toString(),"-Action",action).directory(root.toFile()).redirectOutput(run.resolve("broker-"+action+"-"+workerNumber+".log").toFile()).redirectErrorStream(true).start();assertTrue(process.waitFor(60,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"Owned Kafka lifecycle "+action);}
    boolean portOpen(int port){try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),200);return true;}catch(Exception e){return false;}}
    PreparedStatement statement(String sql,Object...args)throws SQLException{var statement=db.prepareStatement(sql);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;}
    void sql(String sql,Object...args)throws SQLException{try(var statement=statement(sql,args)){statement.executeUpdate();}}
    long number(String sql,Object...args){try(var statement=statement(sql,args);var rows=statement.executeQuery()){assertTrue(rows.next());return rows.getLong(1);}catch(Exception e){throw new IllegalStateException("Database assertion failed at "+stage,e);}}
    String text(String sql,Object...args){try(var statement=statement(sql,args);var rows=statement.executeQuery()){assertTrue(rows.next());return rows.getString(1);}catch(Exception e){throw new IllegalStateException("Database assertion failed at "+stage,e);}}
    void await(BooleanSupplier condition,long millis,String name)throws Exception{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<end){if(condition.getAsBoolean()){check(true,name);return;}Thread.sleep(25);}check(false,name);}
    void check(boolean value,String name){checks.put(name,value);assertTrue(value,name+" at "+stage);}
    void kill(Process process)throws Exception{if(process!=null&&process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(15,TimeUnit.SECONDS));}}
    String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    Map<String,Object> summary(List<Double> values){return values.isEmpty()?Map.of("samples",0):Map.of("samples",values.size(),"meanMillis",values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),"p95Millis",values.stream().sorted().toList().get((int)Math.ceil(values.size()*.95)-1),"rawMillis",values);}
    void finish(String output)throws Exception{report.put("lastStage",stage);report.put("finishedAt",Instant.now().toString());List<String> cleanup=new ArrayList<>();for(Worker worker:workers)try{stop(worker);}catch(Exception e){cleanup.add("worker stop failure");}for(Process process:owned)try{kill(process);}catch(Exception e){cleanup.add("owned process cleanup failure");}report.put("cleanupFailures",cleanup);if(redisFactory!=null)redisFactory.destroy();if(admin!=null)admin.close(Duration.ofSeconds(5));if(db!=null){for(long p:posts)try{sql("UPDATE xl_post SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE id=?",p);}catch(Exception e){cleanup.add("fixture soft-delete failure");}db.close();}Files.writeString(run.resolve(output),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));Files.writeString(evidence.resolve(output),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
}
