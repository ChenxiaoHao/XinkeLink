package cn.xinkelink.operations;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.notification.NotificationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

import static cn.xinkelink.operations.OperationsModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles({"db","auth"})
class OperationsTransactionsIT {
    @Autowired AnnouncementService announcements;
    @Autowired NotificationTaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean NotificationService notifications;
    private AuthPrincipal admin;
    private long user;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @BeforeEach void fixture() {
        String identity=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,'fixture','事务测试员',UTC_TIMESTAMP(3))","op-tx-"+identity);
        long id=jdbc.queryForObject("SELECT id FROM xl_admin WHERE login_name=?",Long.class,"op-tx-"+identity);
        admin=new AuthPrincipal(id,1,"admin");
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id) VALUES ('operations-tx-it',?)",identity);
        user=jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='operations-tx-it' AND wechat_open_id=?",Long.class,identity);
    }
    @AfterEach void cleanup() {
        if(admin==null)return;
        jdbc.update("DELETE FROM xl_user_notification WHERE announcement_id IN (SELECT id FROM xl_announcement WHERE created_by=?) OR task_id IN (SELECT id FROM xl_notification_task WHERE created_by=?)",admin.subjectId(),admin.subjectId());
        jdbc.update("DELETE FROM xl_announcement WHERE created_by=?",admin.subjectId());
        jdbc.update("DELETE FROM xl_notification_task WHERE created_by=?",admin.subjectId());
        jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id=?",user);
        jdbc.update("DELETE FROM xl_user WHERE id=?",user);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE admin_id=?",Integer.class,admin.subjectId())==0)
            jdbc.update("DELETE FROM xl_admin WHERE id=?",admin.subjectId());
        else jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?",admin.subjectId());
    }

    @Test void failureAfterRealAnnouncementReceiptsRollsBackPublishedStateAndEveryReceipt() {
        long id=announcement();
        NotificationService target=AopTestUtils.getUltimateTargetObject(notifications);
        doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("controlled receipt failure");}).when(target).announcement(id);
        assertThrows(IllegalStateException.class,()->announcements.publish(id,new ConfirmCommand(0L,true),admin));
        assertEquals("draft",jdbc.queryForObject("SELECT status FROM xl_announcement WHERE id=?",String.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT version FROM xl_announcement WHERE id=?",Integer.class,id));
        assertNull(jdbc.queryForObject("SELECT first_published_at FROM xl_announcement WHERE id=?",java.sql.Timestamp.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE announcement_id=?",Integer.class,id));
    }
    @Test void failureAfterRealTaskReceiptsLeavesDraftAndNoPartiallySentReceipts() {
        long id=task();String fingerprint=(String)tasks.preview("all",admin).get("audienceFingerprint");
        NotificationService target=AopTestUtils.getUltimateTargetObject(notifications);
        doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("controlled receipt failure");}).when(target).systemTask(eq(id),anyList());
        assertThrows(IllegalStateException.class,()->tasks.send(id,new SendTask(0L,fingerprint,true),admin));
        assertEquals("draft",jdbc.queryForObject("SELECT status FROM xl_notification_task WHERE id=?",String.class,id));
        assertNull(jdbc.queryForObject("SELECT recipient_count FROM xl_notification_task WHERE id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE task_id=?",Integer.class,id));
    }
    @Test void moreThan5000RecipientsRejectBothPublicationAndSendWithoutExposingLargeFixtures() {
        long announcement=announcement(),task=task();TransactionTemplate transaction=new TransactionTemplate(transactions);
        BusinessException publish=assertThrows(BusinessException.class,()->transaction.execute(status->{
            addUncommittedUsers(5001);return announcements.publish(announcement,new ConfirmCommand(0L,true),admin);
        }));
        assertEquals("AUDIENCE_TOO_LARGE",publish.code().name());
        assertEquals("draft",jdbc.queryForObject("SELECT status FROM xl_announcement WHERE id=?",String.class,announcement));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE announcement_id=?",Integer.class,announcement));
        BusinessException send=assertThrows(BusinessException.class,()->transaction.execute(status->{
            addUncommittedUsers(5001);String fingerprint=(String)tasks.preview("all",admin).get("audienceFingerprint");
            return tasks.send(task,new SendTask(0L,fingerprint,true),admin);
        }));
        assertEquals("AUDIENCE_TOO_LARGE",send.code().name());
        assertEquals("draft",jdbc.queryForObject("SELECT status FROM xl_notification_task WHERE id=?",String.class,task));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE task_id=?",Integer.class,task));
    }
    @Test void exactly5000RecipientsAreAcceptedAndCountMatchesAllRealReceiptRows() {
        long task=task();TransactionTemplate transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.execute(status->{
            // 固定边界测试的账号集合，避免其他并行测试恰好新增账号使5000变5001。
            jdbc.queryForList("SELECT id FROM xl_user ORDER BY id FOR SHARE",Long.class);
            int existing=jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE deleted_at IS NULL",Integer.class);
            assertTrue(existing<5000);addUncommittedUsers(5000-existing);
            String fingerprint=(String)tasks.preview("all",admin).get("audienceFingerprint");
            var result=tasks.send(task,new SendTask(0L,fingerprint,true),admin);
            assertEquals(5000,result.get("recipientCount"));
            assertEquals(5000,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE task_id=?",Integer.class,task));
            // 大夹具与其收件记录只在本事务可见，验证后整体回滚，不触发提交后推送。
            status.setRollbackOnly();return null;
        });
        assertEquals("draft",jdbc.queryForObject("SELECT status FROM xl_notification_task WHERE id=?",String.class,task));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE task_id=?",Integer.class,task));
    }
    @Test void concurrentCreateRetriesCommitOneAnnouncementAndOneTask() throws Exception {
        String request=UUID.randomUUID().toString();
        var announcement=new CreateAnnouncement("并发公告","正文","safety","all",request);
        var task=new CreateTask("并发通知","正文","all",request);
        assertEquals(1,concurrently(()->(String)announcements.create(announcement,admin).get("id")).stream().distinct().count());
        assertEquals(1,concurrently(()->(String)tasks.create(task,admin).get("id")).stream().distinct().count());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_announcement WHERE created_by=? AND client_request_id=?",Integer.class,admin.subjectId(),request));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_notification_task WHERE created_by=? AND client_request_id=?",Integer.class,admin.subjectId(),request));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE admin_id=? AND action='create' AND outcome='success'",Integer.class,admin.subjectId()));
    }
    @Test void concurrentPublicationRetriesGenerateOneFirstPublishReceiptPerRecipient() throws Exception {
        long id=announcement();
        assertEquals(List.of(1L,1L),concurrently(()->(Long)announcements.publish(id,new ConfirmCommand(0L,true),admin).get("version")));
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE deleted_at IS NULL",Integer.class),jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE announcement_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE announcement_id=? AND recipient_id=?",Integer.class,id,user));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE admin_id=? AND action='publish' AND outcome='success'",Integer.class,admin.subjectId()));
    }
    @Test void staleVersionsCannotOverwriteDraftsAndUnchangedDraftDoesNotIncrementVersion() {
        long announcement=announcement(),task=task();
        BusinessException unchanged=assertThrows(BusinessException.class,()->announcements.edit(announcement,new EditAnnouncement("事务公告","正文","safety","all",0L),admin));
        assertEquals("STATE_UNCHANGED",unchanged.code().name());
        announcements.edit(announcement,new EditAnnouncement("新公告","正文","safety","all",0L),admin);
        BusinessException stale=assertThrows(BusinessException.class,()->announcements.edit(announcement,new EditAnnouncement("过期覆盖","正文","safety","all",0L),admin));
        assertEquals("VERSION_CONFLICT",stale.code().name());
        tasks.edit(task,new EditTask("新任务","正文","all",0L),admin);
        BusinessException taskStale=assertThrows(BusinessException.class,()->tasks.edit(task,new EditTask("过期覆盖","正文","all",0L),admin));
        assertEquals("VERSION_CONFLICT",taskStale.code().name());
    }
    private long announcement() { return Long.parseLong((String)announcements.create(new CreateAnnouncement("事务公告","正文","safety","all",UUID.randomUUID().toString()),admin).get("id")); }
    private long task() { return Long.parseLong((String)tasks.create(new CreateTask("事务通知","正文","all",UUID.randomUUID().toString()),admin).get("id")); }
    private void addUncommittedUsers(int count) {
        String prefix=UUID.randomUUID().toString();List<Object[]> batch=new ArrayList<>();for(int i=0;i<count;i++)batch.add(new Object[]{prefix+":"+i});
        jdbc.batchUpdate("INSERT INTO xl_user(wechat_app_id,wechat_open_id) VALUES ('operations-cap-it',?)",batch);
    }
    private static <T> List<T> concurrently(Callable<T> action)throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            Callable<T> run=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return action.call();};
            var first=executor.submit(run);var second=executor.submit(run);start.countDown();
            return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
        }
    }
}
