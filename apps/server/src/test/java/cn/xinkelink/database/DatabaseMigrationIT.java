package cn.xinkelink.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实MySQL和独立迁移账号验收；连接与凭据由db配置读取环境变量。
 * 约束夹具仅在回滚事务中创建，不创建或删除数据库，不写入真实学生资料。
 */
@SpringBootTest
@ActiveProfiles("db")
class DatabaseMigrationIT {
    private static final byte[] REQUEST_HASH = new byte[32];

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "xl_user", "xl_user_preference", "xl_user_block", "xl_admin", "xl_auth_session",
            "xl_post", "xl_comment", "xl_like", "xl_favorite",
            "xl_conversation", "xl_conversation_member", "xl_message",
            "xl_report", "xl_moderation_action", "xl_announcement", "xl_notification_task",
            "xl_user_notification", "xl_audit_log", "xl_system_setting", "xl_image_asset",
            "xl_media_ref", "xl_user_activity_day", "xl_beta_stage", "xl_beta_code", "xl_feedback",
            "xl_notification_stream", "xl_notification_outbox", "xl_notification_inbox", "xl_notification_failure",
            "xl_view_receipt", "xl_view_event", "xl_view_daily", "xl_view_visitor_day", "xl_view_projection", "xl_view_failure",
            "xl_lucky_bag", "xl_lucky_display", "xl_lucky_entry", "xl_lucky_result", "xl_lucky_claim", "xl_lucky_operation", "xl_ai_knowledge", "xl_ai_knowledge_version", "xl_ai_knowledge_chunk", "xl_ai_knowledge_index", "xl_ai_session", "xl_ai_request",
            "xl_ai_runtime", "xl_ai_usage", "xl_ai_reindexed_vector");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void usesRealMysql84WithUtcAndReadCommittedConnections() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(8, connection.getMetaData().getDatabaseMajorVersion());
            assertEquals(4, connection.getMetaData().getDatabaseMinorVersion());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
        }
        assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        assertEquals("READ-COMMITTED",
                jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class));
    }

    @Test
    void appliesAllMigrationsAndValidatesRepeatedMigrationWithoutChanges() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                  AND LEFT(table_name, 3) = 'xl_'
                """, String.class);
        assertEquals(EXPECTED_TABLES.size(), tables.size());
        assertEquals(EXPECTED_TABLES, new HashSet<>(tables));

        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20"),
                Arrays.stream(flyway.info().applied()).map(info -> info.getVersion().getVersion()).toList());
        assertEquals(20, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class));
        assertDoesNotThrow(flyway::validate);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertDoesNotThrow(flyway::validate);
    }

    @Test
    @Transactional
    @Rollback
    void opensExistingBetaStageWhilePreservingHistoricalCodeBindings() throws SQLException {
        jdbc.update("INSERT INTO xl_beta_stage(id,phase,initialized,version) VALUES(1,'beta',1,7) ON DUPLICATE KEY UPDATE phase='beta',initialized=1,version=7");
        long user=insertUser();
        String code=UUID.randomUUID().toString().substring(0,16);
        jdbc.update("INSERT INTO xl_beta_code(code,user_id,used_at) VALUES(?,?,UTC_TIMESTAMP(3))",code,user);
        var migration=new org.springframework.core.io.ClassPathResource("db/migration/V11__open_access.sql");
        var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,migration);
        assertEquals("open",jdbc.queryForObject("SELECT phase FROM xl_beta_stage WHERE id=1",String.class));
        assertEquals(8L,jdbc.queryForObject("SELECT version FROM xl_beta_stage WHERE id=1",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT initialized FROM xl_beta_stage WHERE id=1",Integer.class));
        assertEquals(user,jdbc.queryForObject("SELECT user_id FROM xl_beta_code WHERE code=?",Long.class,code));
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,migration);
        assertEquals(8L,jdbc.queryForObject("SELECT version FROM xl_beta_stage WHERE id=1",Long.class));
    }

    @Test
    @Transactional
    @Rollback
    void retiresPrizeNoticesWithoutDeletingHistoryOrHidingOtherNotices() {
        long recipient = insertUser();
        long activity = insertLuckyBag(insertAdmin());
        jdbc.update("""
                INSERT INTO xl_user_notification(recipient_id,event_key,type,event_kind,target_type,target_id,title,summary)
                VALUES (?,'retired-prize','announcement','lucky_bag_win','lucky_bag',?,'历史中奖通知','历史记录'),
                       (?,'ordinary-notice','announcement','system',NULL,NULL,'站内通知','通知正文')
                """, recipient, activity, recipient);
        var migration = new org.springframework.core.io.ClassPathResource("db/migration/V19__retire_lucky_bag_notifications.sql");
        var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, migration);
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, migration);

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=?", Integer.class, recipient));
        assertEquals(List.of("ordinary-notice"), jdbc.queryForList(
                "SELECT event_key FROM xl_user_notification WHERE recipient_id=? AND active=1 AND read_at IS NULL", String.class, recipient));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM xl_user_notification WHERE recipient_id=? AND event_key='retired-prize'", Integer.class, recipient));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM xl_lucky_bag WHERE id=?", Integer.class, activity));
    }

    @Test
    void usesDifferentAuthenticatedUsersForMigrationAndRuntime() throws SQLException {
        String runtimeUser = jdbc.queryForObject("SELECT CURRENT_USER()", String.class);
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT CURRENT_USER()")) {
            assertTrue(result.next());
            assertNotNull(runtimeUser);
            assertNotEquals(runtimeUser.split("@", 2)[0], result.getString(1).split("@", 2)[0]);
        }
    }

    @Test
    void runtimeCannotCreateTablesOrUpdateAndDeleteAuditRows() {
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'xl_user'
                """, Integer.class));
        // 已存在的表配合IF NOT EXISTS，即使误授CREATE权限也不会创建或改变表。
        assertMysqlError(1142, () -> jdbc.execute(
                "CREATE TABLE IF NOT EXISTS xl_user (id BIGINT UNSIGNED NOT NULL PRIMARY KEY)"));
        // 零行条件保证误授权限时也不会改写已有审计记录。
        assertMysqlError(1142, () -> jdbc.update(
                "UPDATE xl_audit_log SET target_count = target_count WHERE 1 = 0"));
        assertMysqlError(1142, () -> jdbc.update("DELETE FROM xl_audit_log WHERE 1 = 0"));
    }

    @Test
    void statisticsEvidenceHasOnlyTheNecessaryRuntimeWritePrivileges() {
        for (String table : List.of("xl_view_event", "xl_view_visitor_day")) {
            assertMysqlError(1142, () -> jdbc.update("UPDATE " + table + " SET post_id=post_id WHERE 1=0"));
        }
        for (String table : List.of("xl_view_receipt", "xl_view_event", "xl_view_daily", "xl_view_visitor_day", "xl_view_projection", "xl_view_failure")) {
            assertMysqlError(1142, () -> jdbc.update("DELETE FROM " + table + " WHERE 1=0"));
        }
    }

    @Test
    void luckyBagEvidenceIsAppendOnlyAndRuntimeCannotDeleteAnyLuckyBagHistory() {
        for (String table : List.of("xl_lucky_entry", "xl_lucky_result", "xl_lucky_operation")) {
            assertMysqlError(1142, () -> jdbc.update("UPDATE " + table + " SET activity_id=activity_id WHERE 1=0"));
        }
        for (String table : List.of("xl_lucky_bag", "xl_lucky_display", "xl_lucky_entry", "xl_lucky_result", "xl_lucky_claim", "xl_lucky_operation")) {
            assertMysqlError(1142, () -> jdbc.update("DELETE FROM " + table + " WHERE 1=0"));
        }
        for (String table : List.of("xl_lucky_bag", "xl_lucky_display", "xl_lucky_claim")) {
            assertEquals(0, jdbc.update("UPDATE " + table + " SET id=id WHERE 1=0"));
        }
    }

    @Test
    void aiArchivesAreAppendOnlyAndOnlyRequestHistoryCanBeDeleted() {
        for (String table : List.of("xl_ai_knowledge_version", "xl_ai_knowledge_chunk")) {
            assertMysqlError(1142, () -> jdbc.update("UPDATE " + table + " SET id=id WHERE 1=0"));
        }
        for (String table : List.of("xl_ai_knowledge", "xl_ai_knowledge_version", "xl_ai_knowledge_chunk", "xl_ai_knowledge_index", "xl_ai_session")) {
            assertMysqlError(1142, () -> jdbc.update("DELETE FROM " + table + " WHERE 1=0"));
        }
        assertEquals(0, jdbc.update("DELETE FROM xl_ai_request WHERE 1=0"));
    }

    @Test
    void luckyBagUniqueKeysAndDueScanIndexesUseTheExactColumnsAndOrder() {
        assertIndex("xl_lucky_entry", "uq_lucky_entry_activity_user", true, "activity_id,user_id");
        assertIndex("xl_lucky_result", "PRIMARY", true, "activity_id");
        assertIndex("xl_lucky_result", "uq_lucky_result_winner", true, "activity_id,winner_user_id");
        assertIndex("xl_lucky_claim", "uq_lucky_claim_activity", true, "activity_id");
        assertIndex("xl_lucky_claim", "uq_lucky_claim_code", true, "code");
        assertIndex("xl_lucky_bag", "idx_lucky_bag_due", false, "status,draw_at,id");
        assertIndex("xl_lucky_entry", "idx_lucky_entry_draw", false, "activity_id,id");
        assertIndex("xl_lucky_operation", "idx_lucky_operation_activity", false, "activity_id,id");
        assertEquals(Set.of("fk_lucky_display_activity", "fk_lucky_entry_activity", "fk_lucky_result_activity",
                "fk_lucky_claim_result", "fk_lucky_claim_winner", "fk_lucky_operation_activity", "fk_lucky_operation_claim"),
                new HashSet<>(jdbc.queryForList("""
                        SELECT constraint_name FROM information_schema.referential_constraints
                        WHERE constraint_schema=DATABASE() AND table_name LIKE 'xl_lucky_%' AND delete_rule='RESTRICT'
                        """, String.class)));
        assertEquals("ascii_bin", jdbc.queryForObject("""
                SELECT collation_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='xl_lucky_claim' AND column_name='code'
                """, String.class));
        assertEquals(32L, jdbc.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='xl_lucky_claim' AND column_name='code'
                """, Long.class));
        for (String constraint : List.of("ck_lucky_display_singleton", "ck_lucky_bag_status", "ck_lucky_bag_prize",
                "ck_lucky_claim_status", "ck_lucky_claim_lifecycle", "ck_lucky_result_participants")) {
            assertEquals("YES", jdbc.queryForObject("""
                    SELECT enforced FROM information_schema.table_constraints
                    WHERE constraint_schema=DATABASE() AND constraint_name=? AND constraint_type='CHECK'
                    """, String.class, constraint), constraint);
        }
    }

    @Test
    @Transactional
    @Rollback
    void luckyBagParticipationAndResultsRemainUniqueAndRequireAConsistentWinner() {
        long admin = insertAdmin(), user = insertUser();
        long activity = insertLuckyBag(admin), otherActivity = insertLuckyBag(admin);
        assertEquals(1, jdbc.update("INSERT INTO xl_lucky_entry(activity_id,user_id) VALUES(?,?)", activity, user));
        assertMysqlError(1062, () -> jdbc.update("INSERT INTO xl_lucky_entry(activity_id,user_id) VALUES(?,?)", activity, user));
        assertEquals(1, jdbc.update("INSERT INTO xl_lucky_entry(activity_id,user_id) VALUES(?,?)", otherActivity, user));
        assertEquals(1, insertLuckyResult(activity, null, 0));
        assertMysqlError(1062, () -> insertLuckyResult(activity, null, 0));
        assertMysqlError(3819, () -> insertLuckyResult(otherActivity, null, 1));
        assertMysqlError(3819, () -> insertLuckyResult(otherActivity, user, 0));
        assertMysqlError(3819, () -> jdbc.update("""
                INSERT INTO xl_lucky_result(activity_id,winner_user_id,participant_count,scheduled_at,drawn_at,algorithm)
                VALUES(?,?,1,'2026-11-17 12:00:00.001','2026-11-17 12:00:00.000','secure-random-v1')
                """, otherActivity, user));
        assertEquals(1, insertLuckyResult(otherActivity, user, 1));
    }

    @Test
    @Transactional
    @Rollback
    void luckyBagSingletonFixedPrizeAndCancellationReasonAreConstrained() {
        long activity = insertLuckyBag(insertAdmin());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM xl_lucky_display WHERE id=1", Integer.class));
        assertEquals(1, jdbc.update("UPDATE xl_lucky_display SET activity_id=? WHERE id=1", activity));
        assertEquals(1, jdbc.update("UPDATE xl_lucky_display SET activity_id=NULL WHERE id=1"));
        assertMysqlError(1452, () -> jdbc.update("UPDATE xl_lucky_display SET activity_id=? WHERE id=1", Long.MAX_VALUE));
        assertMysqlError(3819, () -> jdbc.update("INSERT INTO xl_lucky_display(id,activity_id) VALUES(2,NULL)"));
        assertMysqlError(3819, () -> jdbc.update("UPDATE xl_lucky_bag SET prize_cents=999 WHERE id=?", activity));
        assertMysqlError(3819, () -> jdbc.update("UPDATE xl_lucky_bag SET status='unknown' WHERE id=?", activity));
        assertMysqlError(3819, () -> jdbc.update("UPDATE xl_lucky_bag SET status='cancelled',cancel_reason=NULL WHERE id=?", activity));
        assertMysqlError(3819, () -> jdbc.update("UPDATE xl_lucky_bag SET status='cancelled',cancel_reason=' ' WHERE id=?", activity));
        assertEquals(1, jdbc.update("UPDATE xl_lucky_bag SET status='cancelled',cancel_reason='数据库回滚测试取消' WHERE id=?", activity));
    }

    @Test
    @Transactional
    @Rollback
    void luckyClaimsRequireUniqueActivityAndCodeAndCompleteIssuingAndPaidMetadata() {
        long admin = insertAdmin(), user = insertUser();
        long activity = insertLuckyBag(admin), otherActivity = insertLuckyBag(admin);
        insertLuckyResult(activity, user, 1);
        insertLuckyResult(otherActivity, user, 1);
        String code = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        long nonWinner = insertUser();
        assertMysqlError(1452, () -> jdbc.update("INSERT INTO xl_lucky_claim(activity_id,user_id,code) VALUES(?,?,?)",
                otherActivity, nonWinner, code));
        long claim = insertId("INSERT INTO xl_lucky_claim(activity_id,user_id,code) VALUES(?,?,?)", activity, user, code);
        assertMysqlError(1062, () -> jdbc.update("INSERT INTO xl_lucky_claim(activity_id,user_id,code) VALUES(?,?,?)", activity, user, "F".repeat(32)));
        assertMysqlError(1062, () -> jdbc.update("INSERT INTO xl_lucky_claim(activity_id,user_id,code) VALUES(?,?,?)", otherActivity, user, code));
        assertMysqlError(3819, () -> jdbc.update("INSERT INTO xl_lucky_claim(activity_id,user_id,code) VALUES(?,?,?)", otherActivity, user, "G".repeat(32)));
        assertMysqlError(3819, () -> jdbc.update("UPDATE xl_lucky_claim SET status='unknown' WHERE id=?", claim));
        assertMysqlError(3819, () -> jdbc.update("UPDATE xl_lucky_claim SET status='issuing' WHERE id=?", claim));
        assertMysqlError(3819, () -> jdbc.update("UPDATE xl_lucky_claim SET started_by=? WHERE id=?", admin, claim));
        assertEquals(1, jdbc.update("""
                UPDATE xl_lucky_claim SET status='issuing',started_by=?,started_at=UTC_TIMESTAMP(3),
                    start_operation_id=?,version=version+1 WHERE id=?
                """, admin, UUID.randomUUID().toString(), claim));
        assertMysqlError(3819, () -> jdbc.update("UPDATE xl_lucky_claim SET status='paid' WHERE id=?", claim));
        assertEquals(1, jdbc.update("""
                UPDATE xl_lucky_claim SET status='paid',paid_by=?,paid_at=UTC_TIMESTAMP(3),
                    paid_operation_id=?,version=version+1 WHERE id=?
                """, admin, UUID.randomUUID().toString(), claim));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM xl_lucky_claim WHERE id=?", Integer.class, claim));
    }

    @Test
    @Transactional
    @Rollback
    void statisticsUniqueEventsDailyMembershipAndPublisherLeasesAreConstrained() {
        String event = UUID.randomUUID().toString(), digest = "a".repeat(64), visitor = "b".repeat(64);
        jdbc.update("INSERT INTO xl_view_event(event_id,digest,post_id,visitor_key,occurred_at) VALUES(?,?,1,?,UTC_TIMESTAMP(3))", event, digest, visitor);
        assertMysqlError(1062, () -> jdbc.update("INSERT INTO xl_view_event(event_id,digest,post_id,visitor_key,occurred_at) VALUES(?,?,2,?,UTC_TIMESTAMP(3))", event, digest, visitor));
        jdbc.update("INSERT INTO xl_view_visitor_day(post_id,view_date,visitor_key) VALUES(1,'2026-11-16',?)", visitor);
        assertMysqlError(1062, () -> jdbc.update("INSERT INTO xl_view_visitor_day(post_id,view_date,visitor_key) VALUES(1,'2026-11-16',?)", visitor));
        jdbc.update("INSERT INTO xl_view_visitor_day(post_id,view_date,visitor_key) VALUES(1,'2026-11-17',?)", visitor);
        assertMysqlError(3819, () -> jdbc.update("INSERT INTO xl_view_receipt(event_id,digest,post_id,visitor_key,occurred_at,payload,state) VALUES(?,?,1,?,UTC_TIMESTAMP(3),'{}','LEASED')", event, digest, visitor));
        assertMysqlError(3819, () -> jdbc.update("INSERT INTO xl_view_projection(id,generation) VALUES(2,0)"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name LIKE 'xl_view_%'", Integer.class));
    }

    @Test
    @Transactional
    @Rollback
    void preventsDuplicateWechatIdentityAndKeepsItsBinaryComparison() {
        String openId = "open-" + UUID.randomUUID();
        insertUser("it-database", openId);
        assertMysqlError(1062, () -> insertUser("it-database", openId));
        assertTrue(insertUser("it-other-app", openId) > 0);
        assertTrue(insertUser("it-database", openId.toUpperCase(Locale.ROOT)) > 0);
    }

    @Test
    @Transactional
    @Rollback
    void preventsDuplicateLikesAndFavorites() {
        long user = insertUser();
        long post = insertPost(user);
        assertEquals(1, jdbc.update(
                "INSERT INTO xl_like (user_id, target_type, target_id) VALUES (?, 'post', ?)", user, post));
        assertMysqlError(1062, () -> jdbc.update(
                "INSERT INTO xl_like (user_id, target_type, target_id) VALUES (?, 'post', ?)", user, post));
        assertEquals(1, jdbc.update(
                "INSERT INTO xl_favorite (user_id, target_type, target_id) VALUES (?, 'post', ?)", user, post));
        assertMysqlError(1062, () -> jdbc.update(
                "INSERT INTO xl_favorite (user_id, target_type, target_id) VALUES (?, 'post', ?)", user, post));
    }

    @Test
    @Transactional
    @Rollback
    void preventsDuplicatePendingReportsAndAllowsAReportAfterResolution() {
        long reporter = insertUser();
        long targetUser = insertUser();
        long report = insertReport(reporter, targetUser);
        assertEquals(1, jdbc.queryForObject(
                "SELECT pending_guard FROM xl_report WHERE id = ?", Integer.class, report));
        assertMysqlError(1062, () -> insertReport(reporter, targetUser));

        long admin = insertAdmin();
        assertEquals(1, jdbc.update("""
                UPDATE xl_report SET status = 'processed', resolution = 'reject',
                    resolution_reason = '数据库事务回滚测试结案', resolved_by = ?,
                    resolved_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3), version = version + 1
                WHERE id = ?
                """, admin, report));
        assertNull(jdbc.queryForObject(
                "SELECT pending_guard FROM xl_report WHERE id = ?", Integer.class, report));
        assertTrue(insertReport(reporter, targetUser) > 0);
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM xl_report
                WHERE reporter_id = ? AND target_type = 'user' AND target_id = ?
                """, Integer.class, reporter, targetUser));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM xl_report
                WHERE reporter_id = ? AND target_type = 'user' AND target_id = ? AND status = 'pending'
                """, Integer.class, reporter, targetUser));
    }

    @Test
    @Transactional
    @Rollback
    void requiresExistingRelationOwnersAndRestrictsTheirPhysicalDeletion() {
        long absentUser = Long.MAX_VALUE;
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM xl_user WHERE id = ?", Integer.class, absentUser));
        assertMysqlError(1452, () -> jdbc.update(
                "INSERT INTO xl_user_preference (user_id) VALUES (?)", absentUser));

        long user = insertUser();
        assertEquals(1, jdbc.update("INSERT INTO xl_user_preference (user_id) VALUES (?)", user));
        assertMysqlError(1451, () -> jdbc.update("DELETE FROM xl_user WHERE id = ?", user));
        assertFalse(jdbc.queryForList("SELECT id FROM xl_user WHERE id = ?", Long.class, user).isEmpty());
    }

    private long insertUser() {
        return insertUser("it-database", "open-" + UUID.randomUUID());
    }

    private void assertIndex(String table, String name, boolean unique, String columns) {
        assertEquals(columns, jdbc.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                """, String.class, table, name), table + "." + name);
        assertEquals(unique ? 0 : 1, jdbc.queryForObject("""
                SELECT MIN(non_unique) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                """, Integer.class, table, name), table + "." + name);
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=? AND sub_part IS NOT NULL
                """, Integer.class, table, name), "Deduplication and scans must index complete values");
    }

    private long insertLuckyBag(long admin) {
        return insertId("""
                INSERT INTO xl_lucky_bag(name,draw_at,instructions,wechat,created_by)
                VALUES('数据库回滚测试福袋',UTC_TIMESTAMP(3),'数据库回滚测试领取说明','fixture-wechat',?)
                """, admin);
    }

    private int insertLuckyResult(long activity, Long winner, long participants) {
        return jdbc.update("""
                INSERT INTO xl_lucky_result(activity_id,winner_user_id,participant_count,scheduled_at,drawn_at,algorithm)
                VALUES(?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'secure-random-v1')
                """, activity, winner, participants);
    }

    private long insertUser(String appId, String openId) {
        return insertId("INSERT INTO xl_user (wechat_app_id, wechat_open_id, nickname) VALUES (?, ?, ?)",
                appId, openId, "数据库约束测试");
    }

    private long insertPost(long author) {
        return insertId("""
                INSERT INTO xl_post (author_id, category, content, client_request_id, request_hash)
                VALUES (?, 'campus', '数据库事务回滚夹具', ?, ?)
                """, author, UUID.randomUUID().toString(), REQUEST_HASH);
    }

    private long insertReport(long reporter, long targetUser) {
        return insertId("""
                INSERT INTO xl_report (reporter_id, target_type, target_id, target_user_id,
                    reason_code, description, target_snapshot, client_request_id, request_hash)
                VALUES (?, 'user', ?, ?, 'other', '数据库事务回滚夹具', JSON_OBJECT(), ?, ?)
                """, reporter, targetUser, targetUser, UUID.randomUUID().toString(), REQUEST_HASH);
    }

    private long insertAdmin() {
        return insertId("""
                INSERT INTO xl_admin (login_name, password_hash, display_name, password_changed_at)
                VALUES (?, 'unused-fixture-hash', '数据库测试管理员', CURRENT_TIMESTAMP(3))
                """, "it-admin-" + UUID.randomUUID());
    }

    private long insertId(String sql, Object... arguments) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        assertEquals(1, jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            return statement;
        }, keys));
        Number key = keys.getKey();
        assertNotNull(key);
        return key.longValue();
    }

    private void assertMysqlError(int expectedCode, Executable statement) {
        DataAccessException exception = assertThrows(DataAccessException.class, statement);
        SQLException sqlException = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
        assertEquals(expectedCode, sqlException.getErrorCode(), "数据库必须按预期权限或约束拒绝操作");
    }
}
