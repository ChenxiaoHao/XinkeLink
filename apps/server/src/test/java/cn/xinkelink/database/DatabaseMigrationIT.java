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
            "xl_media_ref", "xl_user_activity_day", "xl_beta_stage", "xl_beta_code", "xl_feedback");

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
        assertEquals(25, tables.size());
        assertEquals(EXPECTED_TABLES, new HashSet<>(tables));

        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"),
                Arrays.stream(flyway.info().applied()).map(info -> info.getVersion().getVersion()).toList());
        assertEquals(9, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class));
        assertDoesNotThrow(flyway::validate);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertDoesNotThrow(flyway::validate);
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
