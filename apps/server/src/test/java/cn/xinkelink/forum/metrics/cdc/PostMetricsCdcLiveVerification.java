package cn.xinkelink.forum.metrics.cdc;

import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.forum.metrics.PostMetricsCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly executed real-CDC verification; not discovered by ordinary Test/IT naming. */
class PostMetricsCdcLiveVerification {
    private final ObjectMapper json=new ObjectMapper();
    private final Path root=Path.of("D:/CODE/XinKeLink"), evidence=Path.of(System.getProperty("canal.live.evidence",root.resolve(".local/canal-20261116").toString()));
    private final Path state=evidence.resolve("live-state");
    private final Path run=evidence.resolve("live-runs").resolve(UUID.randomUUID().toString());
    private final Path jar=Path.of(System.getProperty("canal.live.jar",root.resolve("apps/server/target/canal-final/xinkelink-server-0.1.0-SNAPSHOT.jar").toString()));
    private final List<Process> services=new ArrayList<>();
    private final List<Long> users=new ArrayList<>();
    private final Map<String,Object> report=new LinkedHashMap<>(), assertions=new LinkedHashMap<>();
    private final List<Double> latency=new ArrayList<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private Connection database;
    private Process worker;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private long post,rootComment;
    private String tokenA,tokenB;
    private String stage="setup";
    private int workerRun;

    @Test void realBinlogCanalAndTwoIndependentServiceJvms() throws Exception {
        Files.createDirectories(evidence); Files.createDirectories(state); Files.createDirectories(run);
        for(String control:List.of("stop","redis-fault","network-fault","pause-before-ack","ack-unknown"))Files.deleteIfExists(state.resolve(control));
        report.put("runDirectory",run.toString());
        report.put("assertions",assertions); report.put("failed",true);
        try {
            String url=System.getenv("XINKELINK_DB_URL");
            check(url!=null&&url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_[a-zA-Z0-9_]+_test(?:\\?.*)?"),"isolatedDatabaseOnly");
            check(Files.isRegularFile(jar),"verifiedJarExists");
            report.put("jarSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
            database=DriverManager.getConnection(url,System.getenv("XINKELINK_DB_USERNAME"),System.getenv("XINKELINK_DB_PASSWORD"));
            redisFactory=new LettuceConnectionFactory("127.0.0.1",16379); redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
            startService(18082);startService(18083);
            await(()->healthy(18082)&&healthy(18083),60000,"twoServiceHealth");
            check(services.get(0).pid()!=services.get(1).pid(),"twoIndependentServiceJvms");
            fixture();
            stage="initial-consumer";startWorker();await(()->metric("sharedSuccess")>=1,30000,"initialCanalRelevantEvent");
            await(()->metric("ackReturned")>=1,10000,"initialAckSent");stopWorker(false);

            stage="cdc-off-baseline";warm(0,0);String before=epoch();
            sql("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)",users.get(1),post);
            check(view(18082,"").path("likeCount").asLong()==0&&view(18083,"").path("likeCount").asLong()==0,"withoutCdcApplicationMissRemainsStale");
            check(Objects.equals(before,epoch()),"withoutCdcFenceUnchanged");
            report.put("withoutCdcStaleObservedMillis",System.currentTimeMillis());
            stage="cdc-resume";startWorker();await(()->!Objects.equals(before,epoch())&&metric("sharedSuccess")>=1,30000,"consumerRestartCompensatesDirectSql");
            counts(1,0);checkpoint("initialRecoveryCheckpoint");

            stage="live-latency";
            for(int i=0;i<20;i++) {
                boolean add=i%2!=0;long old=add?0:1;warm(old,0);String token=epoch();long start=System.nanoTime();
                if(add)sql("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)",users.get(1),post);
                else sql("DELETE FROM xl_like WHERE user_id=? AND target_type='post' AND target_id=?",users.get(1),post);
                await(()->!Objects.equals(token,epoch()),1500,"directSqlFenceBeforeL1Expiry"+i);
                counts(add?1:0,0);double millis=(System.nanoTime()-start)/1e6;latency.add(millis);
                check(millis<1800,"twoInstancesFreshBeforeOriginalTtl"+i);
            }
            report.put("cdcLatencySamples",latency.size());report.put("cdcLatencyMeanMs",latency.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
            var sorted=latency.stream().sorted().toList();report.put("cdcLatencyP95Ms",sorted.get((int)Math.ceil(sorted.size()*.95)-1));report.put("cdcLatencyFailures",0);

            stage="comments-and-governance";
            mutate(1,1,()->rootComment=comment(null),"rootCommentInserted");
            long[] reply={0};mutate(1,2,()->reply[0]=comment(rootComment),"replyInserted");
            mutate(1,0,()->sql("UPDATE xl_comment SET status='hidden',version=version+1 WHERE id=?",rootComment),"hiddenRootRemovesReplies");
            mutate(1,2,()->sql("UPDATE xl_comment SET status='visible',version=version+1 WHERE id=?",rootComment),"restoredRootIncludesReplies");
            mutate(1,1,()->sql("UPDATE xl_comment SET deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",reply[0]),"softDeletedReply");
            mutate(1,0,()->sql("UPDATE xl_comment SET deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",rootComment),"softDeletedRoot");
            stage="rollback-and-irrelevant";String unchanged=epoch();long successes=metric("sharedSuccess");
            database.setAutoCommit(false);sql("DELETE FROM xl_like WHERE user_id=? AND target_type='post' AND target_id=?",users.get(1),post);database.rollback();database.setAutoCommit(true);
            sql("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'comment',?)",users.get(1),rootComment);
            sql("UPDATE xl_user SET nickname='CDC live' WHERE id=?",users.get(1));
            // A following relevant post update is an ordered binlog fence proving earlier commits were passed.
            String secondMarker=UUID.randomUUID().toString();sql("INSERT INTO xl_post(author_id,category,content,client_request_id,request_hash) VALUES(?,'campus','CDC ordered marker',?,UNHEX(SHA2(?,256)))",users.get(0),secondMarker,secondMarker);
            long marker=number("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?",users.get(0),secondMarker);
            await(()->Boolean.TRUE.equals(redis.hasKey("xinkelink:cache:v3:post-metrics:generation:"+marker)),10000,"laterRelevantEventProcessed");
            check(Objects.equals(unchanged,epoch()),"rollbackAndUnrelatedTargetsDoNotInvalidatePost");counts(1,0);
            sql("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",marker);

            stage="identity-and-active-invalidation";
            api(18082,"PUT","/api/v1/posts/"+post+"/favorite",tokenA,null,200);
            var guest=view(18083,"");var a=view(18083,tokenA);var b=view(18083,tokenB);
            check(!guest.path("liked").asBoolean()&&!guest.path("favorited").asBoolean()&&!guest.path("isMine").asBoolean(),"guestFlagsRealtime");
            check(!a.path("liked").asBoolean()&&a.path("favorited").asBoolean()&&a.path("isMine").asBoolean(),"accountAFlagsRealtime");
            check(b.path("liked").asBoolean()&&!b.path("favorited").asBoolean()&&!b.path("isMine").asBoolean(),"accountBFlagsRealtime");
            check(guest.path("author").path("id").isNull()&&a.path("author").path("id").isNull()&&b.path("author").path("id").isNull(),"anonymousIdentityRedacted");
            long priorSuccess=metric("sharedSuccess");api(18082,"PUT","/api/v1/posts/"+post+"/like",tokenA,null,200);
            await(()->metric("sharedSuccess")>priorSuccess,10000,"activeInvalidationAndCdcDuplicateSafe");counts(2,0);
            api(18082,"DELETE","/api/v1/posts/"+post+"/like",tokenA,null,200);awaitCounts(1,0);

            stage="old-fill-versus-real-cdc";oldFillAgainstCdc();
            mutate(1,0,()->sql("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)",users.get(1),post),"restoredLikeAfterOldFillRace");
            stage="post-visibility";String visibleEpoch=epoch();sql("UPDATE xl_post SET status='hidden',version=version+1 WHERE id=?",post);
            await(()->!Objects.equals(visibleEpoch,epoch()),10000,"hiddenPostCdcFence");
            check(status(18082,"")==404&&status(18083,"")==404,"hiddenPostStillEnforcesVisibility");
            check(view(18083,tokenA).path("availability").asText().equals("hidden"),"hiddenAuthorProjection");
            mutate(1,0,()->sql("UPDATE xl_post SET status='visible',version=version+1 WHERE id=?",post),"postRestored");

            stage="redis-failure";warm(1,0);String old=epoch();Files.deleteIfExists(state.resolve("redis-blocked.json"));Files.writeString(state.resolve("redis-fault"),"fault");
            sql("DELETE FROM xl_like WHERE user_id=? AND target_type='post' AND target_id=?",users.get(1),post);
            await(()->Files.exists(state.resolve("redis-blocked.json")),10000,"sharedFailureObserved");
            String blocked=json.readTree(Files.readString(state.resolve("redis-blocked.json"))).path("position").asText();
            check(!ackHistoryContains(blocked),"sharedFailureNotAcknowledged");check(Objects.equals(old,epoch()),"sharedFailureDidNotPretendAdvance");
            report.put("redisBlockedPosition",blocked);report.put("redisFaultBoundary","owned RedisTemplate.execute before native command; actual confirmed cache API invoked");
            Files.delete(state.resolve("redis-fault"));await(()->!Objects.equals(old,epoch()),10000,"sharedRecoveryAdvancesFence");counts(0,0);

            stage="before-ack-crash";Files.deleteIfExists(state.resolve("before-ack.json"));Files.writeString(state.resolve("pause-before-ack"),"pause");String oldEpoch=epoch();
            sql("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)",users.get(1),post);
            await(()->Files.exists(state.resolve("before-ack.json"))&&!Objects.equals(oldEpoch,epoch()),10000,"sharedSuccessBeforeAckReached");
            check("PROCESSED".equals(checkpointProperties().getProperty("phase")),"processedDurableBeforeAck");String processedToken=epoch();
            stopWorker(true);Files.delete(state.resolve("pause-before-ack"));startWorker();
            await(()->metric("sharedSuccess")>=1&&!Objects.equals(processedToken,epoch()),15000,"unacknowledgedBatchReplayedAfterCrash");counts(1,0);

            stage="ack-unknown";Files.writeString(state.resolve("ack-unknown"),"fault");sql("DELETE FROM xl_like WHERE user_id=? AND target_type='post' AND target_id=?",users.get(1),post);
            await(()->Files.exists(state.resolve("ack-result-unknown.json")),10000,"unknownAckObserved");Files.delete(state.resolve("ack-unknown"));awaitCounts(0,0);
            stage="network-retry";Files.writeString(state.resolve("network-fault"),"fault");await(()->Files.exists(state.resolve("network-blocked.json")),10000,"networkFaultObserved");
            String networkEpoch=epoch();sql("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)",users.get(1),post);Files.delete(state.resolve("network-fault"));
            await(()->!Objects.equals(networkEpoch,epoch()),10000,"networkRecoveryProcessesPendingEvent");counts(1,0);

            stage="consumer-backlog";stopWorker(false);warm(1,0);String backlogEpoch=epoch();long backlogStart=System.nanoTime();
            sql("DELETE FROM xl_like WHERE user_id=? AND target_type='post' AND target_id=?",users.get(1),post);sql("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)",users.get(1),post);
            startWorker();await(()->metric("sharedSuccess")>=1&&!Objects.equals(backlogEpoch,epoch()),15000,"consumerBacklogRecovered");counts(1,0);
            report.put("consumerBacklogCommittedMutations",2);report.put("consumerBacklogRecoveryMs",(System.nanoTime()-backlogStart)/1e6);checkpoint("backlogRecoveryCheckpoint");

            stage="canal-server-restart";long serverBefore=metric("sharedSuccess");server("Stop");
            sql("DELETE FROM xl_like WHERE user_id=? AND target_type='post' AND target_id=?",users.get(1),post);
            check(api(18082,"GET","/api/v1/posts/"+post,"",null,200)!=null,"forumReadsAvailableWhileCanalStopped");
            api(18082,"PUT","/api/v1/posts/"+post+"/favorite",tokenA,null,200);check(true,"forumWritesAvailableWhileCanalStopped");
            long restartStart=System.nanoTime();server("Start");await(()->metric("sharedSuccess")>serverBefore,45000,"canalServerRestartContinuesFromCursor");awaitCounts(0,0);
            report.put("canalServerRestartRecoveryMs",(System.nanoTime()-restartStart)/1e6);checkpoint("serverRestartCheckpoint");
            stage="paired-http-cost";pairedHttpCost();
            stage="packaged-native-consumer";packagedConsumerProof();
            stage="post-deletion";String deletionEpoch=epoch();sql("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",post);
            await(()->!Objects.equals(deletionEpoch,epoch()),10000,"deletedPostCdcFence");
            check(status(18082,"")==404&&status(18083,"")==404,"deletedPostStillEnforcesVisibility");
            check(view(18083,tokenA).path("availability").asText().equals("deleted"),"deletedAuthorProjection");
            report.put("workerMetrics",metrics());report.put("failed",false);
        } catch(Throwable failure) {
            report.put("failureStage",stage);report.put("failureType",failure.getClass().getSimpleName());
            throw new AssertionError("Live CDC verification failed at sanitized stage: "+stage);
        } finally {
            try {stopWorker(false);}catch(Exception ignored){}
            if(database!=null) {try {for(long id:users){sql("UPDATE xl_post SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE author_id=?",id);sql("UPDATE xl_user SET deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)) WHERE id=?",id);}database.close();report.put("ownFixturesSoftDeleted",true);}catch(Exception ignored){report.put("ownFixturesSoftDeleted",false);}}
            for(Process p:services) if(p.isAlive()){p.destroy();if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly().waitFor();}
            report.put("ownServiceJvmsStopped",services.stream().noneMatch(Process::isAlive));
            if(redisFactory!=null)redisFactory.destroy();
            String output=json.writerWithDefaultPrettyPrinter().writeValueAsString(report);Files.writeString(run.resolve("results.json"),output);Files.writeString(evidence.resolve("live-results.json"),output);
        }
    }

    private void fixture() throws Exception {
        for(int i=0;i<2;i++) {String open=UUID.randomUUID().toString();sql("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('canal-live-it',?,'CDC live')",open);users.add(number("SELECT id FROM xl_user WHERE wechat_app_id='canal-live-it' AND wechat_open_id=?",open));}
        var jwt=new JwtTokenService(new TokenProperties(System.getenv("XINKELINK_JWT_SECRET_BASE64"),"xinkelink"),Clock.systemUTC());
        var tokens=new ArrayList<String>();for(long user:users){sql("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR))",user);long session=number("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",user);tokens.add(jwt.issue("user",user,session,Instant.now().plusSeconds(3600)));}
        tokenA=tokens.get(0);tokenB=tokens.get(1);var created=api(18082,"POST","/api/v1/posts",tokenA,Map.of("content","CDC isolated verification","category","campus","imageIds",List.of(),"anonymous",true,"clientRequestId",UUID.randomUUID().toString()),201);post=created.path("id").asLong();check(post>0,"postCreatedThroughBusinessApi");
        for(int i=0;i<19;i++){String request=UUID.randomUUID().toString();sql("INSERT INTO xl_post(author_id,category,content,client_request_id,request_hash) VALUES(?,'campus','CDC isolated verification',?,UNHEX(SHA2(?,256)))",users.get(0),request,request);}
    }
    private void pairedHttpCost()throws Exception {
        List<Map<String,Object>> samples=new ArrayList<>();
        stopWorker(false);
        for(boolean enabled:List.of(false,true)){
            if(enabled)startWorker();
            for(String route:List.of("detail","list"))for(int concurrency:List.of(1,4)){
                String path=route.equals("detail")?"/api/v1/posts/"+post:"/api/v1/posts?size=20&keyword=CDC%20isolated%20verification";
                for(int i=0;i<80;i++)api(18082,"GET",path,"",null,200);
                Long sqlBefore=selectCounter();Map<String,Long> redisBefore=redisCounters();
                List<Double> times=Collections.synchronizedList(new ArrayList<>());var failures=new java.util.concurrent.atomic.AtomicInteger();
                long start=System.nanoTime();try(var pool=Executors.newFixedThreadPool(concurrency)){
                    var futures=new ArrayList<java.util.concurrent.Future<?>>();
                    for(int w=0;w<concurrency;w++)futures.add(pool.submit(()->{for(int i=0;i<240/concurrency;i++){long t=System.nanoTime();try{api(18082,"GET",path,"",null,200);}catch(Exception failure){failures.incrementAndGet();}times.add((System.nanoTime()-t)/1e6);}}));
                    for(var future:futures)future.get();
                }
                double seconds=(System.nanoTime()-start)/1e9;Long sqlAfter=selectCounter();Map<String,Long> redisAfter=redisCounters();var ordered=times.stream().sorted().toList();
                Map<String,Object> row=new LinkedHashMap<>();row.put("cdc",enabled);row.put("route",route);row.put("concurrency",concurrency);row.put("samples",times.size());row.put("meanMs",times.stream().mapToDouble(Double::doubleValue).average().orElseThrow());row.put("p95Ms",ordered.get((int)Math.ceil(ordered.size()*.95)-1));row.put("failures",failures.get());row.put("requestsPerSecond",240/seconds);row.put("rawMs",ordered);
                if(sqlBefore!=null&&sqlAfter!=null)row.put("serverGlobalComSelectDelta",sqlAfter-sqlBefore);else row.put("sqlCounterUnavailable",true);
                Map<String,Long> delta=new TreeMap<>();redisAfter.forEach((key,value)->delta.put(key,value-redisBefore.getOrDefault(key,0L)));row.put("serverGlobalRedisCommandDelta",delta);samples.add(row);check(failures.get()==0,"pairedHttpNoFailures"+enabled+route+concurrency);
            }
        }
        report.put("pairedHttp",samples);report.put("pairedHttpCounterScope","server-global counters, not isolated per-request Mapper tracing; Canal heartbeat and INFO probes included; no other repository load intended");
    }
    private Long selectCounter(){try(var s=database.createStatement();var rows=s.executeQuery("SHOW GLOBAL STATUS LIKE 'Com_select'")){return rows.next()?Long.valueOf(rows.getString(2)):null;}catch(Exception unavailable){return null;}}
    private Map<String,Long> redisCounters(){try{var properties=redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>)c->c.serverCommands().info("commandstats"));Map<String,Long> result=new TreeMap<>();if(properties!=null)for(String command:List.of("evalsha","eval","get","set","del","publish","info","ping")){String value=properties.getProperty("cmdstat_"+command,"");var match=java.util.regex.Pattern.compile("(?:^|,)calls=(\\d+)").matcher(value);result.put(command,match.find()?Long.parseLong(match.group(1)):0L);}return result;}catch(Exception unavailable){return Map.of();}}
    private void packagedConsumerProof()throws Exception{
        stopWorker(false);var cfg=json.readTree(Files.readString(evidence.resolve("private/cdc.json")));
        try(var socket=new java.net.ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",18084));}
        var builder=new ProcessBuilder(java(),"-jar",jar.toString(),"--spring.profiles.active=db,auth","--server.address=127.0.0.1","--server.port=18084","--logging.level.root=WARN","--xinkelink.cache.post-metrics.cdc.enabled=true");
        var env=builder.environment();env.put("XINKELINK_POST_METRICS_CDC_ENABLED","true");env.put("XINKELINK_POST_METRICS_CDC_HOST","127.0.0.1");env.put("XINKELINK_POST_METRICS_CDC_PORT",cfg.path("canalPort").asText("11111"));env.put("XINKELINK_POST_METRICS_CDC_DESTINATION",cfg.path("destination").asText("post_metrics_test"));env.put("XINKELINK_POST_METRICS_CDC_USERNAME",cfg.path("tcpUsername").asText());env.put("XINKELINK_POST_METRICS_CDC_PASSWORD",cfg.path("tcpPassword").asText());env.put("XINKELINK_POST_METRICS_CDC_SCHEMA",cfg.path("database").asText());env.put("XINKELINK_POST_METRICS_CDC_CHECKPOINT",state.resolve("packaged-checkpoint.properties").toString());
        builder.redirectOutput(run.resolve("packaged-18084.stdout.log").toFile()).redirectError(run.resolve("packaged-18084.stderr.log").toFile());services.add(builder.start());await(()->healthy(18084),60000,"packagedConsumerHealth");
        warm(0,0);String before=epoch();sql("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)",users.get(1),post);await(()->!Objects.equals(before,epoch()),15000,"packagedJarNativeConsumerAdvancesFence");counts(1,0);
        await(()->{try{var props=new Properties();try(var input=Files.newBufferedReader(state.resolve("packaged-checkpoint.properties"))){props.load(input);}return "ACK_SENT".equals(props.getProperty("phase"));}catch(Exception missing){return false;}},10000,"packagedConsumerAckSentCheckpoint");report.put("packagedServiceCdcEnabled",true);
    }
    private void oldFillAgainstCdc()throws Exception {
        redis.delete("xinkelink:cache:v3:post-metrics:"+post);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var controlled=new StringRedisTemplate(redisFactory){
            @Override public <T>T execute(RedisScript<T> script,List<String>keys,Object...args){
                if(script.getScriptAsString().contains("post-metrics-fill")){
                    entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Controlled fill was never released");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Controlled fill interrupted");}
                }
                return super.execute(script,keys,args);
            }
        };
        var meters=new SimpleMeterRegistry();
        var reader=new PostMetricsCache(controlled,true,20,10,true,10000,2000,meters);
        try(var pool=Executors.newSingleThreadExecutor()){
            var oldRead=pool.submit(()->reader.get(List.of(post),ids->{try{return Map.of(post,new PostMetricsCache.Counts(number("SELECT COUNT(*) FROM xl_like WHERE target_type='post' AND target_id=?",post),0));}catch(Exception failure){throw new IllegalStateException("Fixture count read failed");}}));
            try{
                check(entered.await(10,TimeUnit.SECONDS),"oldFillPausedBeforeActualRedis");String token=epoch();
                sql("DELETE FROM xl_like WHERE user_id=? AND target_type='post' AND target_id=?",users.get(1),post);
                await(()->!Objects.equals(token,epoch()),10000,"realCdcAdvancedWhileOldFillPaused");
            }finally{release.countDown();}
            check(oldRead.get(10,TimeUnit.SECONDS).get(post).likeCount()==1,"oldInflightResponseAllowed");
            check(meters.counter("post.metrics.fence.rejected","reason","version").count()==1,"cdcRejectsOldGenerationFill");
            check(reader.get(List.of(post),ids->Map.of(post,new PostMetricsCache.Counts(0,0))).get(post).likeCount()==0,"rejectedCdcFillDidNotPolluteL1OrL2");
            counts(0,0);
        }finally{meters.close();}
    }
    private long comment(Long rootId) throws Exception {String request=UUID.randomUUID().toString();sql("INSERT INTO xl_comment(target_type,target_id,author_id,root_id,reply_to_id,content,client_request_id,request_hash) VALUES('post',?,?,?,?,'CDC isolated comment',?,UNHEX(SHA2(?,256)))",post,users.get(1),rootId,rootId,request,request);return number("SELECT id FROM xl_comment WHERE author_id=? AND client_request_id=?",users.get(1),request);}
    private void mutate(long likes,long comments,Action action,String name) throws Exception {String old=epoch();action.run();await(()->!Objects.equals(old,epoch()),10000,name+"Fence");counts(likes,comments);check(true,name);}
    private void warm(long likes,long comments) throws Exception {counts(likes,comments);}
    private void counts(long likes,long comments) throws Exception {for(int port:List.of(18082,18083)){var v=view(port,"");check(v.path("likeCount").asLong()==likes&&v.path("commentCount").asLong()==comments,"countsAt"+stage+"Port"+port);}}
    private void awaitCounts(long likes,long comments) throws Exception {await(()->{try{for(int port:List.of(18082,18083)){var v=view(port,"");if(v.path("likeCount").asLong()!=likes||v.path("commentCount").asLong()!=comments)return false;}return true;}catch(Exception ignored){return false;}},15000,"eventualCounts"+stage);}
    private JsonNode view(int port,String token)throws Exception{return api(port,"GET","/api/v1/posts/"+post,token,null,200);}
    private int status(int port,String token)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/posts/"+post)).timeout(Duration.ofSeconds(5));if(!token.isEmpty())builder.header("Authorization","Bearer "+token);return http.send(builder.GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
    private String epoch(){return redis.opsForValue().get("xinkelink:cache:v3:post-metrics:generation:"+post);}
    private JsonNode api(int port,String method,String path,String token,Object body,int expected)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8));if(!token.isEmpty())builder.header("Authorization","Bearer "+token);if(body!=null)builder.header("Content-Type","application/json");var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=expected)throw new IllegalStateException("Unexpected HTTP status "+response.statusCode());var tree=json.readTree(response.body());if(path.equals("/api/v1/health"))return tree;if(!tree.path("code").asText().equals("OK"))throw new IllegalStateException("Unexpected API code");return tree.path("data");}
    private boolean healthy(int port){try{return api(port,"GET","/api/v1/health","",null,200).path("status").asText().equals("UP");}catch(Exception ignored){return false;}}
    private void startService(int port)throws Exception{try(var socket=new java.net.ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",port));}var builder=new ProcessBuilder(java(),"-jar",jar.toString(),"--spring.profiles.active=db,auth","--server.address=127.0.0.1","--server.port="+port,"--logging.level.root=WARN","--xinkelink.cache.post-metrics.cdc.enabled=false","--xinkelink.cache.post-metrics.enabled=true","--xinkelink.cache.post-metrics.l1.enabled=true");builder.environment().put("XINKELINK_POST_METRICS_CDC_ENABLED","false");builder.redirectOutput(run.resolve("service-"+port+".stdout.log").toFile()).redirectError(run.resolve("service-"+port+".stderr.log").toFile());services.add(builder.start());}
    private void startWorker()throws Exception{Files.deleteIfExists(state.resolve("stop"));workerRun++;Path arguments=run.resolve("worker-"+workerRun+".args");Files.writeString(arguments,"-cp\n"+argument(System.getProperty("java.class.path"))+"\n"+CdcWorkerProcess.class.getName()+"\n"+argument(state.toString())+"\n");var builder=new ProcessBuilder(java(),"@"+arguments);builder.environment().put("XINKELINK_CDC_PRIVATE_FILE",evidence.resolve("private/cdc.json").toString());builder.redirectOutput(run.resolve("worker-"+workerRun+".stdout.log").toFile()).redirectError(run.resolve("worker-"+workerRun+".stderr.log").toFile());worker=builder.start();await(()->worker.isAlive()&&metric("pid")==worker.pid()&&metrics().path("state").asText().equals("POLLING"),15000,"workerStarted"+workerRun);}
    private void stopWorker(boolean crash)throws Exception{if(worker==null||!worker.isAlive())return;if(crash)worker.destroyForcibly().waitFor();else{Files.writeString(state.resolve("stop"),"stop");if(!worker.waitFor(12,TimeUnit.SECONDS)){worker.destroyForcibly().waitFor();throw new IllegalStateException("Consumer graceful stop timed out");}}}
    private JsonNode metrics(){try{return json.readTree(Files.readString(state.resolve("worker-metrics.json")));}catch(Exception ignored){return json.createObjectNode();}}
    private long metric(String name){return metrics().path(name).asLong();}
    private boolean ackHistoryContains(String position)throws Exception{Path file=state.resolve("ack-history.jsonl");if(!Files.exists(file))return false;for(String line:Files.readAllLines(file)){if(!line.isBlank()&&json.readTree(line).path("position").asText().equals(position))return true;}return false;}
    private Properties checkpointProperties()throws Exception{var p=new Properties();try(var in=Files.newBufferedReader(state.resolve("checkpoint.properties"))){p.load(in);}return p;}
    private void checkpoint(String name)throws Exception{var p=checkpointProperties();report.put(name,Map.of("phase",p.getProperty("phase"),"position",p.getProperty("position"),"batch",p.getProperty("batch_id")));}
    private void server(String action)throws Exception{var p=new ProcessBuilder(System.getenv().getOrDefault("XINKELINK_TEST_PWSH","pwsh"),"-NoProfile","-File",evidence.resolve("canal-server.ps1").toString(),"-Action",action,"-Profile","normal").redirectErrorStream(true).redirectOutput(run.resolve("canal-"+action+".log").toFile()).start();if(!p.waitFor(65,TimeUnit.SECONDS)||p.exitValue()!=0)throw new IllegalStateException("Owned Canal server action failed");}
    private String java(){return Path.of(System.getProperty("java.home"),"bin/java.exe").toString();}
    private String argument(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private void sql(String statement,Object...args)throws Exception{try(var p=database.prepareStatement(statement)){bind(p,args);p.executeUpdate();}}
    private long number(String statement,Object...args)throws Exception{try(var p=database.prepareStatement(statement)){bind(p,args);try(var r=p.executeQuery()){if(!r.next())throw new IllegalStateException("Missing fixture row");return r.getLong(1);}}}
    private void bind(PreparedStatement p,Object[]args)throws Exception{for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);}
    private void check(boolean value,String name){assertions.put(name,value);if(!value)throw new AssertionError("Live assertion failed: "+name);}
    private void await(BooleanSupplier condition,long timeout,String name)throws Exception{long until=System.nanoTime()+Duration.ofMillis(timeout).toNanos();while(System.nanoTime()<until){if(condition.getAsBoolean()){check(true,name);return;}Thread.sleep(20);}check(false,name);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
