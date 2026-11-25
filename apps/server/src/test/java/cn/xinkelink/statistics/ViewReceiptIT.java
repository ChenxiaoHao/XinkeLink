package cn.xinkelink.statistics;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.forum.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles({"db","auth"})
class ViewReceiptIT {
    @Autowired ViewReceiptService receipts;
    @Autowired ViewProcessor processor;
    @Autowired ViewJournal journal;
    @Autowired VisitorCredentials credentials;
    @Autowired ViewStatisticsProperties config;
    @Autowired JdbcTemplate jdbc;
    @Autowired ForumService forum;
    @Autowired PlatformTransactionManager tm;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    AuthPrincipal author;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("xinkelink.statistics.enabled",()->true);
        r.add("xinkelink.statistics.publisher-enabled",()->false);
        r.add("xinkelink.statistics.consumer-enabled",()->false);
        r.add("xinkelink.statistics.projection-enabled",()->false);
        r.add("xinkelink.auth.jwt.secret-base64",()->VisitorCredentialsTest.SECRET);
    }
    @BeforeEach void fixture() {
        config.setEnabled(true);
        String openid="view-it-"+UUID.randomUUID();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('view-it',?, '统计测试')",openid);
        long uid=jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='view-it' AND wechat_open_id=?",Long.class,openid);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",uid);
        author=new AuthPrincipal(uid,jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",Long.class,uid),"user");
    }
    @Test void receiptRetriesKeepServerTimeAndDifferentVisitsIncreasePvOnly() {
        long p=post();String credential=credentials.issue().credential(),id=UUID.randomUUID().toString();
        var first=receipts.receive(new ViewReceiptService.Input(1,id,Long.toString(p),credential),null);
        var retry=receipts.receive(new ViewReceiptService.Input(1,id,Long.toString(p),credential),null);
        assertEquals(first,retry); assertEquals(1,count("xl_view_receipt",p));
        byte[] body=body(id);assertEquals(ViewProcessor.Result.APPLIED,processor.process(body));
        assertEquals(ViewProcessor.Result.DUPLICATE,processor.process(body));
        String next=UUID.randomUUID().toString();receipts.receive(new ViewReceiptService.Input(1,next,Long.toString(p),credential),null);processor.process(body(next));
        assertEquals(2,count("xl_view_event",p));assertEquals(2,pv(p));assertEquals(1,uv(p));
    }
    @Test void differentVisitorsAndLoginIdentityAreVerifiedAndDoNotMerge() {
        long p=post();accept(p,credentials.issue().credential(),null);accept(p,credentials.issue().credential(),null);accept(p,null,author);accept(p,null,author);
        assertEquals(4,pv(p));assertEquals(3,uv(p));
        assertThrows(RuntimeException.class,()->receipts.receive(new ViewReceiptService.Input(1,UUID.randomUUID().toString(),""+p,"forged"),null));
        assertThrows(RuntimeException.class,()->receipts.receive(new ViewReceiptService.Input(1,UUID.randomUUID().toString(),""+p,credentials.issue().credential()),author));
        assertThrows(RuntimeException.class,()->receipts.receive(new ViewReceiptService.Input(1,UUID.randomUUID().toString(),""+p,null),new AuthPrincipal(author.subjectId(),1,"admin")));
    }
    @Test void sameIdConflictAndHiddenOrDeletedPrivateDetailsNeverEnterPublicStatistics() {
        long p=post();String c=credentials.issue().credential(),id=UUID.randomUUID().toString();
        receipts.receive(new ViewReceiptService.Input(1,id,""+p,c),null);
        assertThrows(RuntimeException.class,()->receipts.receive(new ViewReceiptService.Input(1,id,""+p,credentials.issue().credential()),null));
        for(String change:List.of("status='hidden'","status='visible',review_state='pending'","review_state='normal',deleted_at=UTC_TIMESTAMP(3)")) {
            jdbc.update("UPDATE xl_post SET "+change+" WHERE id=?",p);
            assertThrows(RuntimeException.class,()->receipts.receive(new ViewReceiptService.Input(1,UUID.randomUUID().toString(),""+p,null),author));
        }
        assertEquals(1,count("xl_view_receipt",p));
        assertThrows(RuntimeException.class,()->receipts.receive(new ViewReceiptService.Input(1,UUID.randomUUID().toString(),"9223372036854775807",c),null));
    }
    @Test void concurrentSameEventAndVisitorsAreExactlyDeduplicated() throws Exception {
        double before=meters.counter("xinkelink.view.receipt","result","NEW").count();
        long p=post();String id=UUID.randomUUID().toString(),c=credentials.issue().credential();var input=new ViewReceiptService.Input(1,id,""+p,c);
        CountDownLatch go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->{go.await();receipts.receive(input,null);return null;}));go.countDown();for(var t:tasks)t.get(10,TimeUnit.SECONDS);
            byte[] body=body(id);CountDownLatch apply=new CountDownLatch(1);tasks.clear();
            for(int i=0;i<4;i++)tasks.add(pool.submit(()->{apply.await();return processor.process(body);}));apply.countDown();for(var t:tasks)t.get(10,TimeUnit.SECONDS);
            tasks.clear();for(int i=0;i<8;i++)tasks.add(pool.submit(()->{accept(p,credentials.issue().credential(),null);return null;}));for(var t:tasks)t.get(10,TimeUnit.SECONDS);
        }
        assertEquals(9,pv(p));assertEquals(9,uv(p));assertEquals(9,count("xl_view_event",p));
        assertEquals(9,meters.counter("xinkelink.view.receipt","result","NEW").count()-before);
    }
    @Test void failedCommitRollsBackDedupAndBothAggregatesAndReplayThenSucceeds() {
        long p=post();String id=UUID.randomUUID().toString();receipts.receive(new ViewReceiptService.Input(1,id,""+p,null),author);byte[] body=body(id);
        var failing=new ViewProcessor(jdbc,tools.jackson.databind.json.JsonMapper.builder().build(),java.time.Clock.systemUTC()) {
            @Override protected void applied(ViewEvent event) { throw new IllegalStateException("injected transaction failure"); }
        };
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(tm).execute(tx->failing.process(body)));
        assertEquals(0,count("xl_view_event",p));assertEquals(0,pv(p));assertEquals(0,uv(p));
        assertEquals(ViewProcessor.Result.APPLIED,processor.process(body));assertEquals(1,pv(p));
    }
    @Test void payloadMustMatchTrustedReceiptAndOutOfWindowReplayIsDurablyBlocked() {
        long p=post();String id=UUID.randomUUID().toString();receipts.receive(new ViewReceiptService.Input(1,id,""+p,null),author);
        String body=new String(body(id),java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,()->processor.process(body.replace("public_post_detail","search").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(RuntimeException.class,()->processor.process(new byte[2049]));
        assertEquals(0,count("xl_view_event",p));
    }
    @Test void collectionSwitchRejectsNewEventsWithoutDeletingAcceptedBacklog() {
        long p=post();String id=UUID.randomUUID().toString();receipts.receive(new ViewReceiptService.Input(1,id,""+p,null),author);config.setEnabled(false);
        assertThrows(RuntimeException.class,()->receipts.receive(new ViewReceiptService.Input(1,UUID.randomUUID().toString(),""+p,null),author));
        assertEquals(1,count("xl_view_receipt",p));assertEquals(ViewProcessor.Result.APPLIED,processor.process(body(id)));assertEquals(1,pv(p));
    }
    @Test void sentButUnconsumedReceiptIsAutomaticallyRequeuedWithoutChangingItsTimeOrBytes() {
        long p=post();String id=UUID.randomUUID().toString();receipts.receive(new ViewReceiptService.Input(1,id,""+p,null),author);
        byte[] original=body(id);
        jdbc.update("UPDATE xl_view_receipt SET state='SENT',updated_at=TIMESTAMPADD(SECOND,-120,UTC_TIMESTAMP(3)) WHERE event_id=?",id);
        assertTrue(journal.recoverMissing()>0);
        assertEquals("READY",jdbc.queryForObject("SELECT state FROM xl_view_receipt WHERE event_id=?",String.class,id));
        assertArrayEquals(original,body(id));processor.process(original);
        jdbc.update("UPDATE xl_view_receipt SET state='SENT',updated_at=TIMESTAMPADD(SECOND,-120,UTC_TIMESTAMP(3)) WHERE event_id=?",id);
        journal.recoverMissing();assertEquals("SENT",jdbc.queryForObject("SELECT state FROM xl_view_receipt WHERE event_id=?",String.class,id));
    }
    @Test void retainedTrustedBacklogOlderThanThirtyDaysKeepsOriginalDayAndCanStillBeReplayed() {
        long p=post();Instant at=Instant.now().minus(Duration.ofDays(45)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        byte[] bytes=trusted(p,credentials.user(author.subjectId()),at);
        assertEquals(ViewProcessor.Result.APPLIED,processor.process(bytes));
        assertEquals(ViewProcessor.Result.DUPLICATE,processor.process(bytes));
        assertEquals(1,pv(p));
        assertEquals(java.sql.Date.valueOf(at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()),jdbc.queryForObject("SELECT view_date FROM xl_view_daily WHERE post_id=?",java.sql.Date.class,p));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_view_daily WHERE post_id=? AND view_date>=DATE_SUB(DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR),INTERVAL 6 DAY)",Integer.class,p));
    }
    @Test void midnightUsesEventTimeAndCrossDayUvRemainsOneEvenWhenConsumerIsLate() {
        long p=post();LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Instant boundary=today.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();String visitor=credentials.user(author.subjectId());
        processor.process(trusted(p,visitor,boundary.minusMillis(1)));processor.process(trusted(p,visitor,boundary));
        assertEquals(2,pv(p));assertEquals(1,uv(p));assertEquals(2,count("xl_view_daily",p));
        assertThrows(IllegalArgumentException.class,()->processor.process(trusted(p,visitor,Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS))));
    }
    byte[] trusted(long p,String visitor,Instant at){
        var event=new ViewEvent(UUID.randomUUID().toString(),1,"public_post_detail",p,visitor,at);String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(event);
        jdbc.update("INSERT INTO xl_view_receipt(event_id,digest,post_id,visitor_key,occurred_at,payload,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'SENT',?,?)",event.eventId(),ViewEvent.digest(body),p,visitor,java.sql.Timestamp.from(at),body,java.sql.Timestamp.from(at),java.sql.Timestamp.from(at));
        return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    long post(){return Long.parseLong(forum.create(new ForumModels.CreatePost("统计隔离帖子","campus",List.of(),true,UUID.randomUUID().toString()),author).get("id").toString());}
    void accept(long p,String c,AuthPrincipal actor){String id=UUID.randomUUID().toString();receipts.receive(new ViewReceiptService.Input(1,id,""+p,c),actor);processor.process(body(id));}
    byte[] body(String id){return jdbc.queryForObject("SELECT payload FROM xl_view_receipt WHERE event_id=?",String.class,id).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    long count(String table,long p){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE post_id=?",Long.class,p);}
    long pv(long p){return jdbc.queryForObject("SELECT COALESCE(SUM(pv),0) FROM xl_view_daily WHERE post_id=?",Long.class,p);}
    long uv(long p){return jdbc.queryForObject("SELECT COUNT(DISTINCT visitor_key) FROM xl_view_visitor_day WHERE post_id=?",Long.class,p);}
}
