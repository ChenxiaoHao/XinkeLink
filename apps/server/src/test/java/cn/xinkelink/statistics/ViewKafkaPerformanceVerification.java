package cn.xinkelink.statistics;

import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: same-JVM paired detail/receipt cost, commit-paired aggregation, and public ranking queries. */
class ViewKafkaPerformanceVerification {
    final ViewKafkaLiveVerification h=new ViewKafkaLiveVerification();
    final List<Map<String,Object>> phases=new ArrayList<>(),rankPhases=new ArrayList<>();
    final List<Map<String,Object>> visibleSamples=new ArrayList<>();
    ViewKafkaLiveVerification.Worker worker;
    long post;
    int snapshotNumber;
    final Map<String,Integer> allOutcomes=new LinkedHashMap<>(Map.of("SUCCESS",0,"REJECTED",0,"FAILED",0,"UNKNOWN",0));
    final List<Double> pairedCommitDelays=new ArrayList<>();
    record Exchange(int status,double millis,JsonNode data){}
    record Pair(String id,Exchange detail,Exchange view,double pairMillis,String transportFailure){}

    @Test void compareCollectionOffAndOnWithMatchedDetailRequestsAndPublicRankingLoad()throws Exception{
        h.report.put("passed",false);h.report.put("checks",h.checks);h.report.put("detailPhases",phases);h.report.put("rankingPhases",rankPhases);
        try{
            h.setup();h.fixture();worker=h.worker("benchmark","",true,true,true,18086,null,null);post=h.post(false);
            h.report.put("conditions",conditions());
            collection(true);
            // Make this real public post eligible for Top5 without tying ranking to publication date or likes.
            // These setup visitors are excluded from all measured HTTP/SQL intervals.
            for(int i=0;i<20;i++){
                String credential=h.call(18086,"POST","/api/v1/statistics/anonymous-credential",-1,Map.of(),200).path("credential").asString();
                h.receive(18086,post,-1,credential,null);
            }
            h.await(()->h.accepted.stream().allMatch(id->h.eventCount(id)==1)&&lag()==0,60000,"initialSetupAndExistingKafkaReplayDrained");
            h.await(()->rankingPv()==20,15000,"latencyProbePostReallyInPublicTop5");
            phase(false,1,20,false,0);phase(true,1,20,false,0);
            for(int round=0;round<2;round++)for(boolean enabled:round==0?List.of(false,true):List.of(true,false))
                for(int concurrency:List.of(1,4))phase(enabled,concurrency,60,true,round);
            collection(true);
            latencySamples(30);
            for(int i=0;i<20;i++)exchange("GET","/api/v1/posts/view-ranking",-1,null);
            rankingPhase(1,120);rankingPhase(4,120);

            h.stage="performance-reconciliation";
            h.await(()->h.accepted.stream().allMatch(id->h.eventCount(id)==1)&&lag()==0,30000,"performanceAllConfirmedEventsAndOffsetsCaughtUp");
            long unique=0;for(String id:h.accepted)unique+=h.eventCount(id);
            h.check(unique==h.accepted.size()&&h.pv(post)==h.accepted.size(),"performanceConfirmedUniqueAndPvMatch");
            h.check(h.uv(post)==21,"performanceTwentyGuestsAndOneVerifiedUser");
            h.report.put("confirmedUnique",h.accepted.size());h.report.put("persistentUnique",unique);h.report.put("postPv",h.pv(post));h.report.put("postUv",h.uv(post));
            h.report.put("allPairAttemptOutcomesIncludingWarmup",allOutcomes);h.report.put("sameJvmReceiptCommitToAggregateCommit",h.summary(pairedCommitDelays));
            h.report.put("latencySamples",visibleSamples);h.report.put("latencyObservation",Map.of("samples",visibleSamples.size(),"pollMillis",25,"receiveToAggregate",h.summary(numbers(visibleSamples,"receiveAckToAggregateObservedMillis")),"receiveToVisible",h.summary(numbers(visibleSamples,"receiveAckToRankingObservedMillis")),"receiverHttp",h.summary(numbers(visibleSamples,"receiveHttpMillis")),"meaning","Client monotonic observations after durable HTTP acceptance; database and Top5 polling impose an upper-bound observation delay. Commit-paired figures above are separate."));
            h.report.put("observedComparisons",comparisons());
            h.report.put("passed",true);
        }finally{h.finish("performance-results.json");}
    }

    Map<String,Object> conditions(){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("jvm","One JDK21 JVM and current accepted production JAR; test probe loaded via PropertiesLauncher; same three verified accounts, post, no images, authentication and public metric caches enabled");
        result.put("workers","Publisher, consumer and projector remain enabled in BOTH collection modes; only new-receipt collection is toggled. Off mode still drains prior accepted work.");
        result.put("collectionOff","Every sampled visit deliberately attempts one statistics POST and gets 503; this is an expected collection-disabled failure, not an accepted event. The actual mini-program has a bounded retry queue, so this one-attempt benchmark is a lower bound on disabled-mode client requests.");
        result.put("pairedRequests","Each sample issues authenticated GET detail followed by one authenticated statistics POST; the detail page does not wait for the statistics response. Pair wall time is reported as an observation of both requests, not page-render latency.");
        result.put("warmup",Map.of("detailVisitsPerMode",20,"detailHttpRequests",80,"rankingRequests",20,"setupAnonymousVisitors",20));
        result.put("measured",Map.of("rounds",2,"order",List.of("off,on","on,off"),"concurrency",List.of(1,4),"visitsPerModeConcurrencyRound",60,"pairedVisits",480,"detailRequests",480,"statisticsAttempts",480,"rankingSerial",120,"rankingConcurrent4",120,"latencyProbeVisits",30));
        result.put("sqlProbe","Application Statement and PreparedStatement execute invocations (including failed executions), plus JDBC commit acknowledgement counts/timing, classified into http/publisher/consumer/projection/other; driver-internal metadata and external verifier polling excluded. A batch execute is one JDBC invocation. No SQL text, bind values, tokens, credentials or visitor keys exported.");
        result.put("redisProbe","StringRedisTemplate execute/delete/convertAndSend and value get/set/multiGet/increment/setIfAbsent invocation counts, not wire packet count; script-internal Redis operations are not separately counted.");
        result.put("timing","Per-operation monotonic HTTP timings. Receipt/aggregate latency pairs actual JDBC commit return timestamps from the same JVM; JVM scheduling can affect acknowledgement ordering. Separate client observations include polling delay.");
        result.put("backgroundCost","SQL/Redis deltas include worker activity during the interval and its drain, classified separately. Fixed-rate periodic work and one test probe sampler remain present; off and on are not represented as equal total work.");
        result.put("caveat","Short local microbenchmark, finite samples, one broker and shared local MySQL/Redis; reversed order limits but does not eliminate warm-cache and scheduling drift. No claim of production throughput or improvement from asynchronous processing alone.");
        return result;
    }

    void collection(boolean enabled)throws Exception{
        String requested=enabled?"on":"off";
        Files.writeString(worker.state().resolve("collection-mode"),requested);
        h.await(()->{try{return h.json.readTree(Files.readString(worker.state().resolve("collection-mode.json"))).path("mode").asString().equals(requested);}catch(Exception e){return false;}},10000,"collectionMode"+requested+"_"+snapshotNumber);
    }

    void phase(boolean enabled,int concurrency,int samples,boolean measured,int round)throws Exception{
        h.stage="performance-detail-"+(enabled?"on":"off")+"-"+concurrency+"-"+phases.size()+"-warmup"+!measured;
        collection(enabled);
        JsonNode before=snapshotAfter(Instant.now());
        List<Pair> pairs=new ArrayList<>();long wall=System.nanoTime();
        try(var pool=Executors.newFixedThreadPool(concurrency)){
            List<Future<Pair>> requests=new ArrayList<>();
            for(int i=0;i<samples;i++)requests.add(pool.submit(()->{
                String id=UUID.randomUUID().toString();long start=System.nanoTime();Exchange detail=null,view=null;
                try{
                    detail=exchange("GET","/api/v1/posts/"+post,1,null);
                    view=exchange("POST","/api/v1/statistics/post-views",1,Map.of("version",1,"eventId",id,"postId",""+post));
                    return new Pair(id,detail,view,(System.nanoTime()-start)/1e6,"");
                }catch(Exception failure){return new Pair(id,detail,view,(System.nanoTime()-start)/1e6,"TRANSPORT_OR_RESPONSE");}
            }));
            for(var future:requests)pairs.add(future.get(60,TimeUnit.SECONDS));
        }
        double requestWallMillis=(System.nanoTime()-wall)/1e6;
        List<String> phaseAccepted=new ArrayList<>();Map<String,Integer> outcomes=new LinkedHashMap<>(Map.of("SUCCESS",0,"REJECTED",0,"FAILED",0,"UNKNOWN",0));
        Map<Integer,Integer> statuses=new TreeMap<>();int detailSuccess=0,unexpected=0;
        List<Double> detailTimes=new ArrayList<>(),viewTimes=new ArrayList<>(),pairTimes=new ArrayList<>();
        for(Pair pair:pairs){
            if(pair.detail()!=null){detailTimes.add(pair.detail().millis());if(pair.detail().status()==200)detailSuccess++;else unexpected++;}
            else unexpected++;
            if(pair.view()!=null){viewTimes.add(pair.view().millis());statuses.merge(pair.view().status(),1,Integer::sum);}
            pairTimes.add(pair.pairMillis());String outcome=outcome(pair);outcomes.merge(outcome,1,Integer::sum);allOutcomes.merge(outcome,1,Integer::sum);
            if(enabled&&outcome.equals("SUCCESS")){phaseAccepted.add(pair.id());h.accepted.add(pair.id());}
            if(!pair.transportFailure().isEmpty()||pair.view()==null||pair.view().status()!=(enabled?202:503))unexpected++;
        }
        assertEquals(0,unexpected,"All sample statuses must match explicitly declared collection mode");assertEquals(samples,detailSuccess);
        if(enabled){assertEquals(samples,phaseAccepted.size());h.await(()->phaseAccepted.stream().allMatch(id->h.eventCount(id)==1&&h.handled(worker,id)>0),30000,"phaseCommittedAndHandled"+phases.size()+"_"+snapshotNumber);}
        else for(Pair pair:pairs)assertEquals(0,h.eventCount(pair.id()),"Disabled collection cannot manufacture accepted effects");
        JsonNode after=snapshotAfter(Instant.now());List<Double> commits=new ArrayList<>();
        for(String id:phaseAccepted){long receipt=after.path("acceptedCommits").path(id).asLong(),aggregate=after.path("aggregateCommits").path(id).asLong();assertTrue(receipt>0&&aggregate>0,"Both actual commit acknowledgements must be observed");commits.add((aggregate-receipt)/1e6);}
        if(measured){
            Map<String,Object> result=new LinkedHashMap<>();result.put("round",round);result.put("collection",enabled?"on":"off");result.put("concurrency",concurrency);result.put("visits",samples);result.put("detailRequests",samples);result.put("statisticsAttempts",samples);result.put("statisticsRetriesInThisBenchmark",0);result.put("detailHttp",h.summary(detailTimes));result.put("statisticsHttp",h.summary(viewTimes));result.put("pairWall",h.summary(pairTimes));result.put("phaseRequestWallMillis",requestWallMillis);result.put("statisticsStatuses",statuses);result.put("statisticsOutcomes",outcomes);result.put("expectedCollectionDisabled503",enabled?0:samples);result.put("uniqueAccepted",phaseAccepted.size());result.put("receiptCommitToAggregateCommit",h.summary(commits));result.put("jdbcAndRedisCalls",delta(before.path("calls"),after.path("calls")));result.put("jdbcAndRedisNanos",delta(before.path("nanos"),after.path("nanos")));result.put("probeWindowStart",before.path("sampledAt").asString());result.put("probeWindowEnd",after.path("sampledAt").asString());phases.add(result);pairedCommitDelays.addAll(commits);
            progress();
        }
    }

    String outcome(Pair pair){
        if(!pair.transportFailure().isEmpty()||pair.view()==null)return "UNKNOWN";
        if(pair.view().status()==202)return pair.view().data().path("accepted").asBoolean()&&pair.view().data().path("eventId").asString().equals(pair.id())?"SUCCESS":"UNKNOWN";
        if(pair.view().status()>=400&&pair.view().status()<500)return "REJECTED";
        return "FAILED";
    }

    void latencySamples(int count)throws Exception{
        h.stage="performance-latency-observations";
        for(int i=0;i<count;i++){
            String id=UUID.randomUUID().toString();long expectedPv=h.pv(post)+1;
            Exchange received=exchange("POST","/api/v1/statistics/post-views",1,Map.of("version",1,"eventId",id,"postId",""+post));long acknowledged=System.nanoTime();
            assertEquals(202,received.status());assertEquals(id,received.data().path("eventId").asString());h.accepted.add(id);
            h.await(()->h.eventCount(id)==1,15000,"latencyAggregate"+i);double aggregated=(System.nanoTime()-acknowledged)/1e6;
            int[] polls={0};h.await(()->{polls[0]++;return rankingPv()==expectedPv;},15000,"latencyVisible"+i);double visible=(System.nanoTime()-acknowledged)/1e6;
            visibleSamples.add(Map.of("receiveHttpMillis",received.millis(),"receiveAckToAggregateObservedMillis",aggregated,"receiveAckToRankingObservedMillis",visible,"rankingObservationRequests",polls[0]));
        }
    }

    void rankingPhase(int concurrency,int count)throws Exception{
        h.stage="performance-ranking-"+concurrency;JsonNode before=snapshotAfter(Instant.now());List<Double> elapsed=new ArrayList<>();Map<Integer,Integer> statuses=new TreeMap<>();long wall=System.nanoTime();
        try(var pool=Executors.newFixedThreadPool(concurrency)){
            List<Future<Exchange>> requests=new ArrayList<>();for(int i=0;i<count;i++)requests.add(pool.submit(()->exchange("GET","/api/v1/posts/view-ranking",-1,null)));
            for(var request:requests){Exchange result=request.get(30,TimeUnit.SECONDS);elapsed.add(result.millis());statuses.merge(result.status(),1,Integer::sum);assertEquals(200,result.status());assertTrue(result.data().path("available").asBoolean());assertTrue(result.data().path("items").size()<=5);}
        }
        double requestWallMillis=(System.nanoTime()-wall)/1e6;JsonNode after=snapshotAfter(Instant.now());
        Map<String,Object> result=new LinkedHashMap<>();result.put("concurrency",concurrency);result.put("requests",count);result.put("statuses",statuses);result.put("http",h.summary(elapsed));result.put("requestWallMillis",requestWallMillis);result.put("jdbcAndRedisCalls",delta(before.path("calls"),after.path("calls")));result.put("jdbcAndRedisNanos",delta(before.path("nanos"),after.path("nanos")));rankPhases.add(result);progress();
    }

    Exchange exchange(String method,String path,int actor,Object body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18086"+path)).timeout(Duration.ofSeconds(20));
        if(actor>=0)request.header("Authorization","Bearer "+h.tokens.get(actor));if(body!=null)request.header("Content-Type","application/json");
        long start=System.nanoTime();var response=h.http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(h.json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());double elapsed=(System.nanoTime()-start)/1e6;
        return new Exchange(response.statusCode(),elapsed,h.json.readTree(response.body()).path("data"));
    }
    long rankingPv(){try{for(var item:h.ranking(18086).path("items"))if(item.at("/post/id").asLong()==post)return item.path("pv").asLong();return -1;}catch(Exception e){return -1;}}
    long lag(){try{
        var description=h.admin.describeTopics(List.of(h.topic)).allTopicNames().get(5,TimeUnit.SECONDS).get(h.topic);Map<TopicPartition,OffsetSpec> partitions=new HashMap<>();description.partitions().forEach(p->partitions.put(new TopicPartition(h.topic,p.partition()),OffsetSpec.latest()));
        var latest=h.admin.listOffsets(partitions,new ListOffsetsOptions()).all().get(5,TimeUnit.SECONDS);var committed=h.admin.listConsumerGroupOffsets(h.group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);long result=0;
        for(var entry:latest.entrySet()){var offset=committed.get(entry.getKey());result+=Math.max(0,entry.getValue().offset()-(offset==null?0:offset.offset()));}return result;
    }catch(Exception e){return Long.MAX_VALUE;}}
    JsonNode snapshotAfter(Instant minimum)throws Exception{int number=++snapshotNumber;h.await(()->{try{return Instant.parse(h.json.readTree(Files.readString(worker.state().resolve("sql-probe.json"))).path("sampledAt").asString()).isAfter(minimum);}catch(Exception e){return false;}},10000,"probeSnapshot"+number);return h.json.readTree(Files.readString(worker.state().resolve("sql-probe.json")));}
    Map<String,Long> delta(JsonNode before,JsonNode after){Map<String,Long> result=new TreeMap<>();for(var property:after.properties())if(property.getValue().isNumber())result.put(property.getKey(),property.getValue().asLong()-before.path(property.getKey()).asLong());return result;}
    List<Double> numbers(List<Map<String,Object>> entries,String key){return entries.stream().map(row->((Number)row.get(key)).doubleValue()).toList();}
    List<Map<String,Object>> comparisons(){List<Map<String,Object>> result=new ArrayList<>();for(int concurrency:List.of(1,4)){
        List<Double> offDetail=new ArrayList<>(),onDetail=new ArrayList<>(),offStatistics=new ArrayList<>(),onStatistics=new ArrayList<>();
        for(var phase:phases)if(((Number)phase.get("concurrency")).intValue()==concurrency){boolean on=phase.get("collection").equals("on");extractRaw(phase.get("detailHttp"),on?onDetail:offDetail);extractRaw(phase.get("statisticsHttp"),on?onStatistics:offStatistics);}
        double offMean=offDetail.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),onMean=onDetail.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        result.add(Map.of("concurrency",concurrency,"offDetail",h.summary(offDetail),"onDetail",h.summary(onDetail),"detailMeanDifferenceOnMinusOffMillis",onMean-offMean,"offStatisticsFailure",h.summary(offStatistics),"onStatisticsAccepted",h.summary(onStatistics),"interpretation",onMean>offMean?"Measured detail mean increased in this run; background contention and order remain possible contributors.":"Measured detail mean did not increase in this run; this does not establish a performance improvement or zero overhead."));
    }return result;}
    @SuppressWarnings("unchecked") void extractRaw(Object summary,List<Double> target){target.addAll((List<Double>)((Map<String,Object>)summary).get("rawMillis"));}
    void progress()throws Exception{Files.writeString(h.run.resolve("performance-progress.json"),h.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("detailPhases",phases,"rankingPhases",rankPhases,"latencySamples",visibleSamples)));}
}
