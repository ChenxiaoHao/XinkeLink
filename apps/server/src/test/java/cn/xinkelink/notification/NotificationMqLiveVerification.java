package cn.xinkelink.notification;

import cn.xinkelink.auth.token.*;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.client.consumer.*;
import org.apache.rocketmq.common.message.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real MySQL/RocketMQ/packaged-service-JVM verification; never silently skipped by the MQ default. */
class NotificationMqLiveVerification {
    final Path root=Path.of("D:/CODE/XinKeLink"),evidence=Path.of(System.getProperty("notification.live.evidence",root.resolve(".local/rocketmq-20261116").toString())),run=evidence.resolve("live-runs/"+UUID.randomUUID());
    final Path jar=Path.of(System.getProperty("notification.live.jar",root.resolve("apps/server/target/rocketmq/xinkelink-server-0.1.0-SNAPSHOT.jar").toString()));
    final Path brokerScript=Path.of(System.getProperty("notification.live.broker-script",evidence.resolve("rocketmq-server.ps1").toString()));
    final int brokerPort=Integer.getInteger("notification.live.broker-port",20911);
    final JsonMapper json=JsonMapper.builder().build();final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final Map<String,Object> report=new LinkedHashMap<>(),checks=new LinkedHashMap<>();final List<Process> owned=new ArrayList<>();final List<WebSocket> sockets=new ArrayList<>();
    final List<Long> users=new ArrayList<>();final List<String> tokens=new ArrayList<>();
    Connection db;Process a,b,worker;Path workerState;int workerNumber,workerPort=18084;String stage="setup";DefaultMQProducer replayProducer;
    @Test void actualNotificationsSurviveDuplicatesCrashesOutagesAndDeadLetterReplay()throws Exception{
        Files.createDirectories(run);report.put("runDirectory",run.toString());report.put("checks",checks);report.put("passed",false);
        try{
            String url=System.getenv("XINKELINK_DB_URL");check(url!=null&&url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_[a-zA-Z0-9_]+_test(?:\\?.*)?"),"isolatedSchema");
            check(Files.isRegularFile(jar),"packagedJarPresent");report.put("jarSha256",sha(Files.readAllBytes(jar)));
            db=DriverManager.getConnection(url,System.getenv("XINKELINK_DB_USERNAME"),System.getenv("XINKELINK_DB_PASSWORD"));
            check(number("SELECT @@log_bin")==1&&text("SELECT @@binlog_row_image").equals("FULL"),"binlogPreserved");
            a=service(18082,false,false);b=service(18083,true,true);check(a.pid()!=b.pid(),"independentServiceJvms");fixture();
            stage="online-and-duplicate";
            var local=new Listener();var remote=new Listener();socket(18083,tokens.get(0),local);socket(18082,tokens.get(0),remote);
            long p=post(true);like(p,0,true);check(outbox(p).isEmpty(),"selfLikeNoEvent");
            like(p,1,true);String event=event(p);await(()->notices(p)==1,30000,"twoJvmUniqueNotice");
            var online=local.event("notification.created");check(online.path("payload").path("eventKind").asString().equals("post_like"),"consumerInstanceOnlineWebsocket");
            check(remote.events.poll(300,TimeUnit.MILLISECONDS)==null,"otherInstanceSocketNotPromised");
            check(call(18083,"GET","/api/v1/notifications",0,null,200).path("items").size()==1,"crossInstanceHttpVisible");
            long notice=number("SELECT id FROM xl_user_notification WHERE event_key=?","post_like:"+p+":"+users.get(1));
            check(call(18083,"PUT","/api/v1/notifications/"+notice+"/read",0,null,200).path("changed").asBoolean(),"readOnce");
            check(!call(18083,"PUT","/api/v1/notifications/"+notice+"/read",0,null,200).path("changed").asBoolean(),"readIdempotent");
            call(18083,"PUT","/api/v1/notifications/"+notice+"/read",2,null,404);call(18083,"GET","/api/v1/notifications",-1,null,401);
            send(event);send(event);await(()->number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id=?",event)==1,10000,"oneInboxAfterNativeReplay");
            check(notices(p)==1&&number("SELECT COUNT(*) FROM xl_user_notification WHERE id=? AND read_at IS NOT NULL",notice)==1,"duplicatePreservesReadAndCount");
            like(p,1,false);like(p,1,true);await(()->number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id IN (SELECT event_id FROM xl_notification_outbox WHERE post_id=?)",p)==3,30000,"cancelAndRelikeConsumed");
            check(notices(p)==1&&number("SELECT active FROM xl_user_notification WHERE id=?",notice)==0,"cancelThenRelikeNeverRenotifies");
            stage="anonymous-comments";String rootComment=comment(p,1,null);await(()->number("SELECT COUNT(*) FROM xl_user_notification WHERE event_key=?","comment:"+rootComment)==1,20000,"rootCommentDelivered");
            var actorSocket=new Listener();socket(18083,tokens.get(1),actorSocket);String reply=comment(p,0,rootComment);
            JsonNode replyEvent=actorSocket.event("notification.created").path("payload");check(replyEvent.at("/actor/user/isAnonymous").asBoolean()&&replyEvent.at("/actor/user/id").isNull(),"anonymousReplyIdentitySafe");
            String third=comment(p,2,rootComment);await(()->number("SELECT COUNT(*) FROM xl_user_notification WHERE event_key=?","comment:"+third)==2,20000,"commentDistinctRecipients");
            check(number("SELECT COUNT(*) FROM xl_user_notification WHERE event_key=? AND recipient_id=?","comment:"+third,users.get(2))==0,"commentActorExcluded");
            // Persisted notification is independent of the online device and the instance owning its socket.
            sockets.forEach(WebSocket::abort);sockets.clear();check(call(18082,"GET","/api/v1/notifications",1,null,200).path("items").size()>=1,"offlineHttpRecovery");
            stage="worker-isolation";kill(b);b=service(18083,false,false);
            stage="hidden-before-consume";long hidden=post(false);like(hidden,1,true);sql("UPDATE xl_post SET status='hidden' WHERE id=?",hidden);
            long deleted=post(false);like(deleted,1,true);sql("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",deleted);startWorker("normal",true,true);
            await(()->notices(hidden)==1&&notices(deleted)==1,30000,"hiddenAndDeletedIntentsStillPersisted");
            var projected=findNotice(hidden);check(projected.path("quote").isNull()&&projected.path("image").isNull()&&projected.path("title").asString().equals("内容已不可用")&&projected.at("/actor/user/id").isNull(),"safeProjectionAfterHide");
            sql("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",hidden);check(findNotice(hidden).path("quote").isNull(),"deletedProjectionSafe");stopWorker();
            startWorker("normal",false,true);await(()->notices(deleted)==1,30000,"deletedBeforeConsumeIntentPersisted");check(findNotice(deleted).path("quote").isNull()&&findNotice(deleted).at("/actor/user/id").isNull(),"deletedBeforeConsumeProjectionSafe");stopWorker();
            stage="claim-crash";long claimPost=post(false);like(claimPost,1,true);String claimed=event(claimPost);startWorker("claim-halt",true,false);
            crashMarker("claim-halt",71);check(text("SELECT state FROM xl_notification_outbox WHERE event_id=?",claimed).equals("LEASED")&&notices(claimPost)==0,"claimCrashRetained");
            startWorker("normal",true,true);await(()->notices(claimPost)==1,30000,"expiredClaimRecovered");check(number("SELECT attempts FROM xl_notification_outbox WHERE event_id=?",claimed)>=2,"claimReclaimedWithStableId");stopWorker();
            stage="sent-crash";long sentPost=post(false);like(sentPost,1,true);String sent=event(sentPost);startWorker("sent-halt",true,false);crashMarker("sent-halt",72);
            String firstBroker=json.readTree(Files.readString(workerState.resolve("sent-halt.json"))).path("brokerId").asString();check(text("SELECT state FROM xl_notification_outbox WHERE event_id=?",sent).equals("LEASED"),"sendBeforeOutboxCrashRetained");
            startWorker("normal",true,true);await(()->notices(sentPost)==1&&text("SELECT state FROM xl_notification_outbox WHERE event_id=?",sent).equals("SENT"),30000,"sendBeforeOutboxCrashRecovered");
            check(!firstBroker.equals(text("SELECT broker_message_id FROM xl_notification_outbox WHERE event_id=?",sent))&&number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id=?",sent)==1,"sameBusinessIdAcrossBrokerDuplicates");stopWorker();
            stage="unknown-result-injection";long unknownPost=post(false);like(unknownPost,1,true);String unknown=event(unknownPost);startWorker("unknown",true,true);
            await(()->Files.exists(workerState.resolve("unknown.json"))&&text("SELECT state FROM xl_notification_outbox WHERE event_id=?",unknown).equals("SENT"),30000,"unknownResultRecoversSameEvent");
            await(()->metric("xinkelink.notification.send[","UNKNOWN")>=1,5000,"unknownMetricRecorded");
            await(()->number("SELECT attempts FROM xl_notification_outbox WHERE event_id=?",unknown)>=2&&notices(unknownPost)==1,30000,"unknownResultNoDuplicateEffect");stopWorker();
            stage="consume-before-ack-crash";long consumePost=post(false);like(consumePost,1,true);String consumed=event(consumePost);startWorker("consume-halt",true,true);crashMarker("consume-halt",73);
            check(number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id=?",consumed)==1&&notices(consumePost)==1,"consumeCommitPrecedesAckCrash");
            long consumedNotice=number("SELECT id FROM xl_user_notification WHERE event_key=?","post_like:"+consumePost+":"+users.get(1));call(18083,"PUT","/api/v1/notifications/"+consumedNotice+"/read",0,null,200);
            startWorker("normal",true,true);await(()->metric("xinkelink.notification.consume[","DUPLICATE")>=1,30000,"unackedNativeMessageReplayed");
            check(notices(consumePost)==1&&number("SELECT COUNT(*) FROM xl_user_notification WHERE id=? AND read_at IS NOT NULL",consumedNotice)==1,"ackCrashReplayPreservesRead");
            stage="broker-outage";broker("Stop");check(!portOpen(brokerPort),"brokerActuallyStopped");long stoppedPost=post(false);like(stoppedPost,1,true);String stoppedEvent=event(stoppedPost);
            check(notices(stoppedPost)==0&&number("SELECT COUNT(*) FROM xl_notification_outbox WHERE event_id=?",stoppedEvent)==1,"brokerDownBusinessCommitsOutbox");
            long recovering=System.nanoTime();broker("Start");await(()->notices(stoppedPost)==1,90000,"brokerRestartDrainsOutbox");report.put("brokerRestartRecoveryMillis",(System.nanoTime()-recovering)/1e6);stopWorker();
            stage="consumer-backlog";startWorker("normal",true,false);List<Long> backlog=new ArrayList<>();for(int i=0;i<8;i++){long next=post(false);like(next,1,true);backlog.add(next);}
            await(()->backlog.stream().allMatch(id->text("SELECT state FROM xl_notification_outbox WHERE event_id=?",event(id)).equals("SENT")),30000,"publisherRunsWithoutConsumer");check(backlog.stream().allMatch(id->notices(id)==0),"consumerStoppedBacklog");stopWorker();
            long resumed=System.nanoTime();startWorker("normal",false,true);await(()->backlog.stream().allMatch(id->notices(id)==1),40000,"consumerRestartDrainsBrokerBacklog");report.put("consumerBacklogCount",backlog.size());report.put("consumerRecoveryMillis",(System.nanoTime()-resumed)/1e6);stopWorker();
            lateAndParallel();
            stage="retry-dlq";long poisonPost=post(false);like(poisonPost,1,true);String poisonEvent=event(poisonPost);startWorker("poison",true,true);
            // Hook is enabled before startup; native retries and DLQ routing remain entirely real.
            await(()->number("SELECT COUNT(*) FROM xl_notification_failure WHERE event_id=? AND state='QUARANTINED'",poisonEvent)==1,90000,"boundedRetryDurableQuarantine");
            check(notices(poisonPost)==0&&number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id=?",poisonEvent)==0,"poisonNeverRecordedAsInboxSuccess");
            await(()->dlqContains(poisonEvent),30000,"actualBrokerDlqContainsEvent");stopWorker();
            stage="repair-replay";operatorReplay(poisonEvent);startWorker("normal",true,true);await(()->notices(poisonPost)==1&&text("SELECT state FROM xl_notification_failure WHERE event_id=?",poisonEvent).equals("RESOLVED"),30000,"manualReplayAfterRepair");
            check(number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id=?",poisonEvent)==1,"replayUniqueInbox");
            report.put("unknownResultTest","adapter injection after real SEND_OK, not physical network outage");report.put("passed",true);
        }finally{
            report.put("lastStage",stage);report.put("finishedAt",Instant.now().toString());
            sockets.forEach(WebSocket::abort);try{stopWorker();}catch(Exception cleanup){report.put("workerStopError",cleanup.getClass().getSimpleName());}
            if(replayProducer!=null)replayProducer.shutdown();for(Process p:owned)if(p.isAlive())kill(p);
            if(db!=null)db.close();Files.writeString(run.resolve("results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));Files.writeString(evidence.resolve("live-results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
    void lateAndParallel()throws Exception{
        stage="native-cancel-first";long p=post(false);like(p,1,true);like(p,1,false);like(p,1,true);var events=outbox(p);assertEquals(3,events.size());startWorker("normal",false,true);
        send(events.get(1));await(()->number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id=?",events.get(1))==1,20000,"nativeCancelConsumedFirst");
        send(events.get(0));send(events.get(2));await(()->number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id IN (SELECT event_id FROM xl_notification_outbox WHERE post_id=?)",p)==3,20000,"nativeLateCreateAndRelikeConsumed");
        check(notices(p)==0,"nativeLateCreateCannotRestoreCancelledInteraction");Files.writeString(workerState.resolve("publisher-mode"),"enabled");
        await(()->number("SELECT COUNT(*) FROM xl_notification_outbox WHERE post_id=? AND state='SENT'",p)==3,20000,"manuallySentEventsStillDrainedFromOutbox");stopWorker();
        stage="parallel-publishers-consumers";List<Long> posts=new ArrayList<>();for(int i=0;i<20;i++){long next=post(false);like(next,1,true);posts.add(next);}
        startWorker("normal",false,true);Process first=worker;Path firstState=workerState;
        workerPort=18085;try{startWorker("normal",false,true);}finally{workerPort=18084;}
        Files.writeString(firstState.resolve("publisher-mode"),"enabled");Files.writeString(workerState.resolve("publisher-mode"),"enabled");
        await(()->posts.stream().allMatch(id->notices(id)==1),40000,"twoPublisherConsumerJvmsDeliverUniqueEffects");
        await(()->metric("xinkelink.notification.send[","OK")>0&&metricAt(firstState,"xinkelink.notification.send[","OK")>0,10000,"bothIndependentPublishersSent");
        check(posts.stream().allMatch(id->number("SELECT COUNT(*) FROM xl_notification_inbox WHERE event_id=?",event(id))==1),"parallelInboxUnique");
        stopWorker();Files.writeString(firstState.resolve("stop"),"stop");assertTrue(first.waitFor(20,TimeUnit.SECONDS));report.put("parallelBusinessEvents",posts.size());
    }
    void fixture()throws Exception{var jwt=new JwtTokenService(new TokenProperties(System.getenv("XINKELINK_JWT_SECRET_BASE64"),"xinkelink"),Clock.systemUTC());for(int i=0;i<3;i++){String open=UUID.randomUUID().toString();sql("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('mq-live-it',?,'通知专项用户')",open);long id=number("SELECT id FROM xl_user WHERE wechat_app_id='mq-live-it' AND wechat_open_id=?",open);users.add(id);sql("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(3)))",id);long session=number("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",id);tokens.add(jwt.issue("user",id,session,Instant.now().plusSeconds(3600)));}}
    long post(boolean anonymous)throws Exception{return call(18082,"POST","/api/v1/posts",0,Map.of("content","MQ隔离验收正文","category","campus","imageIds",List.of(),"anonymous",anonymous,"clientRequestId",UUID.randomUUID().toString()),201).path("id").asLong();}
    void like(long p,int user,boolean add)throws Exception{call(18082,add?"PUT":"DELETE","/api/v1/posts/"+p+"/like",user,null,200);}
    String comment(long p,int user,String reply)throws Exception{var body=new LinkedHashMap<String,Object>();body.put("content","MQ隔离验收评论");body.put("clientRequestId",UUID.randomUUID().toString());if(reply!=null)body.put("replyToCommentId",reply);return call(18082,"POST","/api/v1/posts/"+p+"/comments",user,body,201).at("/comment/id").asString();}
    JsonNode call(int port,String method,String path,int user,Object body,int status)throws Exception{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(user>=0)request.header("Authorization","Bearer "+tokens.get(user));if(body!=null)request.header("Content-Type","application/json");var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(status,response.statusCode(),"HTTP status at "+stage+" "+method+" "+path);return json.readTree(response.body()).path("data");}
    JsonNode findNotice(long p)throws Exception{for(var n:call(18083,"GET","/api/v1/notifications?size=50",0,null,200).path("items"))if(n.at("/target/id").asLong()==p)return n;return fail("Expected notification missing at "+stage);}
    int notices(long p){return (int)number("SELECT COUNT(*) FROM xl_user_notification WHERE target_type='post' AND target_id=? AND recipient_id=?",p,users.getFirst());}
    List<String> outbox(long p){try(var s=statement("SELECT event_id FROM xl_notification_outbox WHERE post_id=? ORDER BY sequence_no",p);var r=s.executeQuery()){List<String> values=new ArrayList<>();while(r.next())values.add(r.getString(1));return values;}catch(Exception e){throw new IllegalStateException(e);}}
    String event(long p){return outbox(p).getFirst();}
    PreparedStatement statement(String sql,Object...args)throws SQLException{var s=db.prepareStatement(sql);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
    void sql(String sql,Object...args)throws SQLException{try(var s=statement(sql,args)){s.executeUpdate();}}
    long number(String sql,Object...args){try(var s=statement(sql,args);var r=s.executeQuery()){assertTrue(r.next());return r.getLong(1);}catch(Exception e){throw new IllegalStateException("Database assertion failed at "+stage,e);}}
    String text(String sql,Object...args){try(var s=statement(sql,args);var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}catch(Exception e){throw new IllegalStateException("Database assertion failed at "+stage,e);}}
    String java(){return Path.of(System.getProperty("java.home"),"bin/java.exe").toString();}
    List<String> jvm(){return new ArrayList<>(List.of(java(),"-Xms64m","-Xmx256m","-XX:MaxDirectMemorySize=96m","-XX:ActiveProcessorCount=2","-Drocketmq.client.logRoot="+run.resolve("client-logs")));}
    List<String> arguments(int port,boolean publisher,boolean consumer){return List.of("--spring.profiles.active=db,auth","--server.address=127.0.0.1","--server.port="+port,"--logging.level.root=WARN","--xinkelink.notification.mq.enabled=true","--xinkelink.notification.mq.publisher-enabled="+publisher,"--xinkelink.notification.mq.consumer-enabled="+consumer);}
    Process service(int port,boolean publisher,boolean consumer)throws Exception{List<String> cmd=jvm();cmd.addAll(List.of("-jar",jar.toString()));cmd.addAll(arguments(port,publisher,consumer));Process p=launch(cmd,"service-"+port+"-"+owned.size());await(()->p.isAlive()&&healthy(port),60000,"serviceHealthy"+port+"-"+owned.size());return p;}
    Process launch(List<String> cmd,String name)throws Exception{var builder=new ProcessBuilder(cmd).directory(root.resolve("apps/server").toFile()).redirectOutput(run.resolve(name+".stdout.log").toFile()).redirectError(run.resolve(name+".stderr.log").toFile());var p=builder.start();owned.add(p);return p;}
    boolean healthy(int port){try{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}catch(Exception e){return false;}}
    void startWorker(String mode,boolean publisher,boolean consumer)throws Exception{workerState=run.resolve("worker-"+(++workerNumber)+"-"+mode);Files.createDirectories(workerState);if(mode.equals("poison"))Files.writeString(workerState.resolve("poison"),"fault");List<String> cmd=jvm();Path argumentFile=workerState.resolve("arguments.txt");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));Files.writeString(argumentFile,"-cp\n\""+cp.replace("\\","/")+"\"\n");cmd.add("@"+argumentFile);cmd.addAll(List.of(NotificationMqWorkerProcess.class.getName(),workerState.toString(),mode));cmd.addAll(arguments(workerPort,publisher,consumer));worker=launch(cmd,"worker-"+workerNumber+"-"+mode);if(!mode.endsWith("halt"))await(()->worker.isAlive()&&Files.exists(workerState.resolve("ready.json")),60000,"workerReady"+workerNumber);}
    void stopWorker()throws Exception{if(worker==null||!worker.isAlive())return;Files.writeString(workerState.resolve("stop"),"stop");if(!worker.waitFor(20,TimeUnit.SECONDS)){kill(worker);throw new IllegalStateException("Worker normal stop timed out");}}
    void crashMarker(String phase,int exit)throws Exception{await(()->Files.exists(workerState.resolve(phase+".json")),60000,phase+"Marker");assertTrue(worker.waitFor(10,TimeUnit.SECONDS));check(worker.exitValue()==exit,phase+"ActualJvmHalt");}
    double metric(String prefix,String label){return metricAt(workerState,prefix,label);}
    double metricAt(Path state,String prefix,String label){try{var values=json.readTree(Files.readString(state.resolve("metrics.json")));double total=0;for(var property:values.properties())if(property.getKey().startsWith(prefix)&&property.getKey().contains(label)&&property.getKey().endsWith(".COUNT"))total+=property.getValue().asDouble();return total;}catch(Exception e){return 0;}}
    void broker(String action)throws Exception{var p=new ProcessBuilder(System.getenv("XINKELINK_TEST_PWSH"),"-NoProfile","-File",brokerScript.toString(),"-Action",action).directory(root.toFile()).redirectOutput(run.resolve("broker-"+action+"-"+workerNumber+".log").toFile()).redirectErrorStream(true).start();assertTrue(p.waitFor(60,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),"Broker lifecycle "+action);}
    boolean portOpen(int port){try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),200);return true;}catch(Exception e){return false;}}
    void producer()throws Exception{if(replayProducer==null){replayProducer=new DefaultMQProducer("xl_manual_verification_"+UUID.randomUUID());replayProducer.setNamesrvAddr(System.getenv("XINKELINK_NOTIFICATION_MQ_NAMESRV_ADDR"));replayProducer.setVipChannelEnabled(false);replayProducer.setInstanceName(UUID.randomUUID().toString());replayProducer.start();}}
    void send(String id)throws Exception{producer();Message message=new Message(System.getenv("XINKELINK_NOTIFICATION_MQ_TOPIC"),"intent_v1",id,text("SELECT payload FROM xl_notification_outbox WHERE event_id=?",id).getBytes(java.nio.charset.StandardCharsets.UTF_8));check(replayProducer.send(message).getSendStatus()==SendStatus.SEND_OK,"nativeReplaySend"+checks.size());}
    boolean dlqContains(String eventId){DefaultMQPullConsumer pull=new DefaultMQPullConsumer("xl_dlq_inspect_"+UUID.randomUUID());try{pull.setNamesrvAddr(System.getenv("XINKELINK_NOTIFICATION_MQ_NAMESRV_ADDR"));pull.setInstanceName(UUID.randomUUID().toString());pull.setVipChannelEnabled(false);pull.start();String topic="%DLQ%"+System.getenv("XINKELINK_NOTIFICATION_MQ_CONSUMER_GROUP");for(var queue:pull.fetchSubscribeMessageQueues(topic)){long offset=pull.minOffset(queue);var result=pull.pull(queue,"*",offset,32);if(result.getMsgFoundList()!=null)for(var m:result.getMsgFoundList())if(json.readTree(m.getBody()).path("eventId").asString().equals(eventId))return true;}return false;}catch(Exception e){return false;}finally{pull.shutdown();}}
    void operatorReplay(String eventId)throws Exception{String schema=text("SELECT DATABASE()");var p=new ProcessBuilder(System.getenv("XINKELINK_TEST_PWSH"),"-NoProfile","-File",root.resolve("scripts/manage-notification-outbox.ps1").toString(),"-Action","Replay","-Database",schema,"-EventId",eventId).redirectOutput(run.resolve("operator-replay.log").toFile()).redirectErrorStream(true).start();assertTrue(p.waitFor(15,TimeUnit.SECONDS));assertEquals(0,p.exitValue());}
    void socket(int port,String token,Listener listener){sockets.add(http.newWebSocketBuilder().header("Authorization","Bearer "+token).connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/v1/ws"),listener).join());}
    class Listener implements WebSocket.Listener{final BlockingQueue<JsonNode> events=new LinkedBlockingQueue<>();final StringBuilder fragments=new StringBuilder();@Override public void onOpen(WebSocket socket){socket.request(1);}@Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){fragments.append(text);if(last){events.add(json.readTree(fragments.toString()));fragments.setLength(0);}socket.request(1);return null;}JsonNode event(String type)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){var e=events.poll(100,TimeUnit.MILLISECONDS);if(e!=null&&e.path("type").asString().equals(type))return e;}return fail("WebSocket event missing: "+type);}}
    void await(BooleanSupplier condition,long millis,String name)throws Exception{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<end){if(condition.getAsBoolean()){check(true,name);return;}Thread.sleep(25);}check(false,name);}
    void check(boolean value,String name){checks.put(name,value);assertTrue(value,name+" at "+stage);}
    void kill(Process p)throws Exception{if(p!=null&&p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(15,TimeUnit.SECONDS));}}
    String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
