package cn.xinkelink.admin;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.operations.AnnouncementService;
import cn.xinkelink.operations.NotificationTaskService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.util.*;
import java.util.concurrent.*;

import static cn.xinkelink.operations.OperationsModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles({"db","auth"})
class AdminAuditIdempotencyIT {
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AnnouncementService announcements;
    @MockitoSpyBean NotificationTaskService tasks;
    AuthPrincipal admin;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(new byte[32]));}
    @BeforeEach void fixture(){
        String login="admin-audit-race-"+UUID.randomUUID();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,'fixture','审计并发测试员',UTC_TIMESTAMP(3))",login);
        admin=new AuthPrincipal(jdbc.queryForObject("SELECT id FROM xl_admin WHERE login_name=?",Long.class,login),1,"admin");
    }
    @AfterEach void cleanup(){
        jdbc.update("DELETE FROM xl_user_notification WHERE announcement_id IN (SELECT id FROM xl_announcement WHERE created_by=?) OR task_id IN (SELECT id FROM xl_notification_task WHERE created_by=?)",admin.subjectId(),admin.subjectId());
        jdbc.update("DELETE FROM xl_announcement WHERE created_by=?",admin.subjectId());
        jdbc.update("DELETE FROM xl_notification_task WHERE created_by=?",admin.subjectId());
        jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?",admin.subjectId());
    }
    @Test void concurrentAnnouncementCreatesRecordOnlyTheActualInsert()throws Exception {
        var input=new CreateAnnouncement("并发审计公告","正文","safety","all",UUID.randomUUID().toString());
        AnnouncementService target=AopTestUtils.getUltimateTargetObject(announcements);CountDownLatch entered=new CountDownLatch(2);
        doAnswer(call->{entered.countDown();assertTrue(entered.await(5,TimeUnit.SECONDS));return call.callRealMethod();}).when(target).create(eq(input),eq(admin));
        var results=concurrently(()->announcements.create(input,admin));
        assertEquals(1,results.stream().map(row->row.get("id")).distinct().count());
        assertEquals(1,successes("announcements","create"));
        assertFalse(results.getFirst().containsKey("changed"));
    }
    @Test void concurrentNotificationCreatesRecordOnlyTheActualInsert()throws Exception {
        var input=new CreateTask("并发审计通知","正文","all",UUID.randomUUID().toString());
        NotificationTaskService target=AopTestUtils.getUltimateTargetObject(tasks);CountDownLatch entered=new CountDownLatch(2);
        doAnswer(call->{entered.countDown();assertTrue(entered.await(5,TimeUnit.SECONDS));return call.callRealMethod();}).when(target).create(eq(input),eq(admin));
        var results=concurrently(()->tasks.create(input,admin));
        assertEquals(1,results.stream().map(row->row.get("id")).distinct().count());
        assertEquals(1,successes("notifications","create"));
        assertFalse(results.getFirst().containsKey("changed"));
    }
    @Test void concurrentPublicationRetriesRecordOnlyTheActualTransition()throws Exception {
        long id=Long.parseLong((String)announcements.create(new CreateAnnouncement("并发发布审计","正文","safety","all",UUID.randomUUID().toString()),admin).get("id"));
        AnnouncementService target=AopTestUtils.getUltimateTargetObject(announcements);CountDownLatch entered=new CountDownLatch(2);var input=new ConfirmCommand(0L,true);
        doAnswer(call->{entered.countDown();assertTrue(entered.await(5,TimeUnit.SECONDS));return call.callRealMethod();}).when(target).publish(eq(id),eq(input),eq(admin));
        var results=concurrently(()->announcements.publish(id,input,admin));
        assertEquals(List.of(1L,1L),results.stream().map(row->row.get("version")).toList());
        assertEquals(1,successes("announcements","publish"));
        assertEquals(1,jdbc.queryForObject("SELECT version FROM xl_announcement WHERE id=?",Integer.class,id));
    }
    private int successes(String module,String action){return jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE admin_id=? AND module=? AND action=? AND outcome='success'",Integer.class,admin.subjectId(),module,action);}
    private static <T>List<T> concurrently(Callable<T> action)throws Exception{try(var executor=Executors.newFixedThreadPool(2)){var first=executor.submit(action);var second=executor.submit(action);return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));}}
}
