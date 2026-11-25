package cn.xinkelink.notification;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.forum.*;
import cn.xinkelink.notification.mq.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Committed MySQL transactions; no class-level test transaction or sleep-based notification assertions. */
@SpringBootTest
@ActiveProfiles({"db","auth"})
class NotificationMqIT {
    @Autowired ForumService forum;
    @Autowired NotificationService notices;
    @Autowired JdbcTemplate jdbc;
    @Autowired InboxProcessor inbox;
    @MockitoSpyBean InteractionJournal journal;
    InteractionJournal journalTarget;
    @Autowired NotificationMqProperties config;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean NotificationMapper mapper;
    AuthPrincipal author,actor,third;
    java.sql.Connection existingBacklogFence;
    final List<Long> users=new ArrayList<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("xinkelink.auth.jwt.secret-base64",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("xinkelink.notification.mq.enabled",()->true);
        r.add("xinkelink.notification.mq.publisher-enabled",()->false);
        r.add("xinkelink.notification.mq.consumer-enabled",()->false);
    }
    @BeforeEach void fixture()throws Exception{journalTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(journal);reset(journalTarget);config.setEnabled(true);author=user();actor=user();third=user();
        // Leave previously retained verification backlog untouched. SKIP LOCKED must only see this test's new rows.
        existingBacklogFence=Objects.requireNonNull(jdbc.getDataSource()).getConnection();existingBacklogFence.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);existingBacklogFence.setAutoCommit(false);
        try(var statement=existingBacklogFence.prepareStatement("SELECT event_id FROM xl_notification_outbox WHERE state IN ('READY','LEASED','PAUSED') FOR UPDATE");var rows=statement.executeQuery()){while(rows.next()){/* Only existing row locks, no updates and no READ_COMMITTED gap lock. */}}
    }
    @Test void businessRollbackRollsBackLikeStreamAndOutbox(){long p=post(false);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{forum.likePost(p,true,actor);assertEquals(1,bodies(p).size());tx.setRollbackOnly();});
        assertTrue(bodies(p).isEmpty());assertEquals(0,count("xl_like","target_type='post' AND target_id="+p));assertEquals(0,raw(author));
    }
    @Test void outboxFailureRollsBackActualForumTransaction(){long p=post(false);
        doAnswer(invocation->{invocation.callRealMethod();return invocation.callRealMethod();}).when(journalTarget).append(any());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->forum.likePost(p,true,actor));
        assertEquals(0,count("xl_like","target_type='post' AND target_id="+p));assertTrue(bodies(p).isEmpty());
    }
    @Test void mqCreatesOnlyAfterConsumerAndDuplicateDoesNotResetRead(){long p=post(false);forum.likePost(p,true,actor);
        assertEquals(0,raw(author));byte[] body=bodies(p).getFirst();assertEquals(InboxProcessor.Result.APPLIED,inbox.process(body));
        long id=notice(p);notices.read(id,author);
        assertEquals(InboxProcessor.Result.DUPLICATE,inbox.process(body));assertEquals(0,raw(author));assertEquals(1,countNotices(p));
    }
    @Test void cancellationBeforeCreateAndRelikeNeverRestoresNotice(){long p=post(false);forum.likePost(p,true,actor);forum.likePost(p,false,actor);forum.likePost(p,true,actor);
        var bodies=bodies(p);assertEquals(3,bodies.size());
        inbox.process(bodies.get(1));assertEquals(InboxProcessor.Result.STALE,inbox.process(bodies.get(0)));inbox.process(bodies.get(2));
        assertEquals(0,raw(author));assertEquals(0,countNotices(p));
    }
    @Test void createConsumedThenCancelAndDuplicateCreateLeaveInactiveReadTombstone(){long p=post(false);forum.likePost(p,true,actor);byte[] create=bodies(p).getFirst();inbox.process(create);
        long id=notice(p);notices.read(id,author);forum.likePost(p,false,actor);inbox.process(bodies(p).get(1));inbox.process(create);forum.likePost(p,true,actor);inbox.process(bodies(p).getLast());
        assertEquals(0,raw(author));assertEquals(1,countNotices(p));assertEquals(0,jdbc.queryForObject("SELECT active FROM xl_user_notification WHERE id=?",Integer.class,id));
        assertNotNull(jdbc.queryForObject("SELECT read_at FROM xl_user_notification WHERE id=?",java.sql.Timestamp.class,id));
    }
    @Test void syncCancellationFencesOldMqAndSyncDoesNotDiscardOtherBacklog(){long p=post(false);forum.likePost(p,true,actor);byte[] old=bodies(p).getFirst();
        config.setEnabled(false);forum.likePost(p,false,actor);assertEquals(InboxProcessor.Result.STALE,inbox.process(old));
        forum.likePost(p,true,actor);assertEquals(0,raw(author));assertEquals(1,bodies(p).size());
        long other=post(false);config.setEnabled(true);forum.likePost(other,true,third);config.setEnabled(false);inbox.process(bodies(other).getFirst());assertEquals(1,raw(author));
    }
    @Test void hiddenBeforeConsumeStillCreatesSafePlaceholderAndAnonymousReplyStaysAnonymous(){long p=post(true);
        String root=comment(p,actor,null);drain(p);comment(p,author,root);drain(p);
        var anonymous=notices.list(null,false,null,20,actor).items().getFirst();assertNull(((Map<?,?>)((Map<?,?>)anonymous.get("actor")).get("user")).get("id"));
        forum.likePost(p,true,third);jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?",p);drain(p);
        for(var n:notices.list(null,false,null,20,author).items()){assertNull(n.get("quote"));assertNull(n.get("image"));assertEquals("内容已不可用",n.get("title"));}
        jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",p);
        assertTrue(notices.list(null,false,null,20,author).items().stream().allMatch(n->n.get("quote")==null));
    }
    @Test void commentIntentDeduplicatesRecipientsExcludesSelfAndRetainsSharedReactions(){long p=post(false);
        forum.likePost(p,true,author);assertEquals(0,bodies(p).size());String root=comment(p,author,null);comment(p,third,root);drain(p);assertEquals(1,raw(author));
        forum.favorite(p,true,actor);forum.likeComment(Long.parseLong(root),true,actor);drain(p);
        assertEquals(3,raw(author));assertEquals(1,count("xl_user_notification","recipient_id="+author.subjectId()+" AND event_kind='post_favorite'"));
    }
    @Test void twoConsumersApplyOnceAndFailedTransactionHasNoInbox(){long p=post(false);forum.likePost(p,true,actor);byte[] body=bodies(p).getFirst();
        doThrow(new IllegalStateException("injected apply failure")).when(mapper).recipientExists(author.subjectId());
        assertThrows(IllegalStateException.class,()->inbox.process(body));assertEquals(0,count("xl_notification_inbox","event_id=(SELECT event_id FROM xl_notification_outbox WHERE natural_key='post_like:"+p+":"+actor.subjectId()+"')"));
        reset(mapper);CountDownLatch go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{go.await();return inbox.process(body);});var b=pool.submit(()->{go.await();return inbox.process(body);});go.countDown();
            assertEquals(Set.of(InboxProcessor.Result.APPLIED,InboxProcessor.Result.DUPLICATE),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));}
        catch(Exception e){throw new AssertionError(e);}assertEquals(1,countNotices(p));
    }
    @Test void brokerPayloadCannotChangePersistedRecipientIntent(){long p=post(false);forum.likePost(p,true,actor);byte[] body=bodies(p).getFirst();
        String forged=new String(body,java.nio.charset.StandardCharsets.UTF_8).replace("\"recipients\":["+author.subjectId()+"]","\"recipients\":["+third.subjectId()+"]");
        assertThrows(IllegalArgumentException.class,()->inbox.process(forged.getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertEquals(0,raw(third));
    }
    @Test void leaseExpiryFencesLateOwnerAndTwoPublishersCannotClaimSameEvent()throws Exception{
        long p=post(false);forum.likePost(p,true,actor);
        var first=journal.claim();assertEquals(1,first.size());assertTrue(journal.claim().isEmpty());var old=first.getFirst();
        jdbc.update("UPDATE xl_notification_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE event_id=?",old.eventId());
        var renewed=journal.claim().getFirst();assertEquals(old.eventId(),renewed.eventId());assertNotEquals(old.token(),renewed.token());
        assertFalse(journal.finish(old,"OK","late"));assertTrue(journal.finish(renewed,"OK","current"));
        assertEquals("current",jdbc.queryForObject("SELECT broker_message_id FROM xl_notification_outbox WHERE event_id=?",String.class,old.eventId()));
    }
    @Test void commitStageFailureRollsBackInboxAndNoticeBeforeReturningToListener(){long p=post(false);forum.likePost(p,true,actor);byte[] body=bodies(p).getFirst();
        var delegate=mockingDetails(mapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation->{org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("injected commit-stage failure");}
        });return delegate.answer(invocation);}).when(mapper).recipientExists(author.subjectId());
        assertThrows(IllegalStateException.class,()->inbox.process(body));assertEquals(0,countNotices(p));
        reset(mapper);assertEquals(InboxProcessor.Result.APPLIED,inbox.process(body));assertEquals(1,countNotices(p));
    }
    @Test void markingReadAndAsyncCancellationSerializeWithoutRevivingUnread()throws Exception{
        long p=post(false);forum.likePost(p,true,actor);inbox.process(bodies(p).getFirst());long id=notice(p);forum.likePost(p,false,actor);byte[] cancel=bodies(p).getLast();
        CountDownLatch readLocked=new CountDownLatch(1),cancelStarted=new CountDownLatch(1);
        var delegate=mockingDetails(mapper).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation->{readLocked.countDown();assertTrue(cancelStarted.await(5,TimeUnit.SECONDS));return delegate.answer(invocation);}).when(mapper).read(id,author.subjectId());
        try(var pool=Executors.newFixedThreadPool(2)){
            var reading=pool.submit(()->notices.read(id,author));assertTrue(readLocked.await(5,TimeUnit.SECONDS));
            var cancellation=pool.submit(()->{cancelStarted.countDown();return inbox.process(cancel);});
            assertEquals(true,reading.get(10,TimeUnit.SECONDS).get("changed"));assertEquals(InboxProcessor.Result.APPLIED,cancellation.get(10,TimeUnit.SECONDS));
        }
        inbox.process(bodies(p).getFirst());assertEquals(0,raw(author));assertEquals(0,jdbc.queryForObject("SELECT active FROM xl_user_notification WHERE id=?",Integer.class,id));
    }
    @Test void exhaustedPublishStaysPausedAndOperatorReplayKeepsExactIntent(){long p=post(false);forum.likePost(p,true,actor);config.setMaxAttempts(1);
        var claim=journal.claim().getFirst();String original=claim.payload();assertTrue(journal.finish(claim,"UNKNOWN",null));
        assertEquals("PAUSED",jdbc.queryForObject("SELECT state FROM xl_notification_outbox WHERE event_id=?",String.class,claim.eventId()));assertTrue(journal.claim().isEmpty());
        assertTrue(journal.replay(claim.eventId()));var replay=journal.claim().getFirst();assertEquals(claim.eventId(),replay.eventId());assertEquals(original,replay.payload());assertTrue(journal.finish(replay,"OK","recovered"));
    }
    @Test void laterBatchClaimsHaveBudgetForEarlierSequentialSends(){long one=post(false),two=post(false);forum.likePost(one,true,actor);forum.likePost(two,true,actor);var claims=journal.claim();assertEquals(2,claims.size());
        long spread=jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,MIN(lease_until),MAX(lease_until)) FROM xl_notification_outbox WHERE event_id IN (?,?)",Long.class,claims.get(0).eventId(),claims.get(1).eventId());
        assertTrue(spread>=config.getSendTimeoutMillis()*1000L,"second lease includes first bounded send budget");
    }
    @Test void twoPublishersClaimDisjointCommittedRows()throws Exception{int previous=config.getBatchSize();config.setBatchSize(2);try{
        for(int i=0;i<4;i++)forum.likePost(post(false),true,actor);CountDownLatch go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{go.await();return journal.claim();});var b=pool.submit(()->{go.await();return journal.claim();});go.countDown();
            var left=a.get(10,TimeUnit.SECONDS);var right=b.get(10,TimeUnit.SECONDS);assertEquals(2,left.size());assertEquals(2,right.size());
            Set<String> ids=new HashSet<>();left.forEach(c->ids.add(c.eventId()));right.forEach(c->ids.add(c.eventId()));assertEquals(4,ids.size());}
    }finally{config.setBatchSize(previous);}}
    private void drain(long p){bodies(p).forEach(inbox::process);}
    private List<byte[]> bodies(long p){return jdbc.queryForList("SELECT payload FROM xl_notification_outbox WHERE post_id=? ORDER BY sequence_no,created_at,event_id",String.class,p).stream().map(s->s.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toList();}
    private int count(String t,String where){return jdbc.queryForObject("SELECT COUNT(*) FROM "+t+" WHERE "+where,Integer.class);}
    private int countNotices(long p){return count("xl_user_notification","target_type='post' AND target_id="+p);}
    private long notice(long p){return jdbc.queryForObject("SELECT id FROM xl_user_notification WHERE target_type='post' AND target_id=? AND recipient_id=?",Long.class,p,author.subjectId());}
    private long raw(AuthPrincipal p){return ((Number)((Map<?,?>)notices.unread(p).get("raw")).get("total")).longValue();}
    private AuthPrincipal user(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES ('mq-it',?,'测试用户')",id);long uid=jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='mq-it' AND wechat_open_id=?",Long.class,id);users.add(uid);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(3)))",uid);
        return new AuthPrincipal(uid,jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",Long.class,uid),"user");}
    private long post(boolean anon){return Long.parseLong(forum.create(new ForumModels.CreatePost("通知私密测试正文","study",List.of(),anon,UUID.randomUUID().toString()),author).get("id").toString());}
    private String comment(long p,AuthPrincipal who,String reply){return ((Map<?,?>)forum.createComment(p,new ForumModels.CreateComment("测试评论",reply,UUID.randomUUID().toString()),who).get("comment")).get("id").toString();}
    @AfterEach void cleanup()throws Exception{if(existingBacklogFence!=null){existingBacklogFence.rollback();existingBacklogFence.close();}config.setEnabled(true);config.setMaxAttempts(12);reset(mapper,journalTarget);
        for(long u:users){var keys=jdbc.queryForList("SELECT natural_key FROM xl_notification_outbox WHERE post_id IN (SELECT id FROM xl_post WHERE author_id=?)",String.class,u);
            for(String key:keys){jdbc.update("DELETE FROM xl_notification_inbox WHERE event_id IN (SELECT event_id FROM xl_notification_outbox WHERE natural_key=?)",key);jdbc.update("DELETE FROM xl_notification_outbox WHERE natural_key=?",key);jdbc.update("DELETE FROM xl_notification_stream WHERE natural_key=?",key);}}
        for(long u:users){jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id=? OR actor_id=?",u,u);jdbc.update("DELETE FROM xl_like WHERE user_id=?",u);jdbc.update("DELETE FROM xl_favorite WHERE user_id=?",u);}
        var comments=new ArrayList<Long>();for(long u:users)comments.addAll(jdbc.queryForList("SELECT id FROM xl_comment WHERE author_id=?",Long.class,u));
        comments.stream().sorted(Comparator.reverseOrder()).forEach(id->jdbc.update("DELETE FROM xl_comment WHERE id=?",id));
        for(long u:users){jdbc.update("DELETE FROM xl_post WHERE author_id=?",u);jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",u);jdbc.update("DELETE FROM xl_user WHERE id=?",u);}
        // Journal has no user FK: reliability/tombstone lifetime is independent of physical fixture deletion.
    }
}
