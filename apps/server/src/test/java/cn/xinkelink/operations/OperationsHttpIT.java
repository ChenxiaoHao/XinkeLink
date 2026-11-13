package cn.xinkelink.operations;

import cn.xinkelink.auth.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 所有业务请求经过真实HTTP端口与MySQL，不替换业务存储。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"db", "auth"})
class OperationsHttpIT {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cn.xinkelink.beta.BetaService platformPhase;
    // Business regressions run in the public phase; beta access has its own integration suite.
    @org.junit.jupiter.api.BeforeEach void publicPlatformFixture(){org.mockito.Mockito.doReturn(false).when(platformPhase).isBeta();}

    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> users = new ArrayList<>();
    private String token;
    private long admin;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach void fixture() {
        String login = "op-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,?,?,UTC_TIMESTAMP(3))",
                login, passwords.encode("test-password"), "公告测试员");
        admin = jdbc.queryForObject("SELECT id FROM xl_admin WHERE login_name=?", Long.class, login);
        token = auth.loginAdmin(login, "test-password").tokens().accessToken();
        user();
    }

    @AfterEach void cleanup() {
        if (admin == 0) return;
        jdbc.update("DELETE FROM xl_user_notification WHERE announcement_id IN (SELECT id FROM xl_announcement WHERE created_by=?) OR task_id IN (SELECT id FROM xl_notification_task WHERE created_by=?)", admin, admin);
        jdbc.update("DELETE FROM xl_announcement WHERE created_by=?", admin);
        jdbc.update("DELETE FROM xl_notification_task WHERE created_by=?", admin);
        for (long user : users) {
            jdbc.update("DELETE FROM xl_user_notification WHERE recipient_id=?", user);
            
            jdbc.update("DELETE FROM xl_user WHERE id=?", user);
        }
        jdbc.update("DELETE FROM xl_auth_session WHERE subject_type='admin' AND subject_id=?", admin);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE admin_id=?", Integer.class, admin) == 0)
            jdbc.update("DELETE FROM xl_admin WHERE id=?", admin);
        else jdbc.update("UPDATE xl_admin SET status='disabled' WHERE id=?", admin);
    }

    @Test void announcementLifecyclePreservesHistoryAndFirstPublishReceipts() throws Exception {
        String request = UUID.randomUUID().toString();
        var input = announcement("校园公告", "forum", request);
        JsonNode draft = data(call("POST", "/admin/announcements", input), 201);
        String id = draft.get("id").stringValue(), path = "/admin/announcements/" + id;
        assertEquals("draft", draft.get("status").stringValue());
        code(call("GET", "/announcements/" + id, null, false), 404, "TARGET_UNAVAILABLE");
        code(call("POST", path + "/publish", Map.of("expectedVersion", 0, "confirmed", false)), 400, "VALIDATION_FAILED");
        JsonNode published = data(call("POST", path + "/publish", command(0)), 200);
        assertEquals("published", published.get("status").stringValue());
        assertNotNull(published.get("firstPublishedAt").stringValue());
        int receipts = jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE announcement_id=?", Integer.class, id);
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE deleted_at IS NULL", Integer.class), receipts);
        assertEquals(1, published.get("version").intValue());
        assertEquals(1, data(call("POST", path + "/publish", command(0)), 200).get("version").intValue());
        assertEquals(id, data(call("GET", "/announcements/" + id, null, false), 200).get("id").stringValue());
        JsonNode edited = data(call("PATCH", path, Map.of("title", "新标题", "body", "新正文", "category", "safety", "scope", "forum", "expectedVersion", 1)), 200);
        assertEquals("draft", edited.get("status").stringValue());
        assertEquals(published.get("firstPublishedAt"), edited.get("firstPublishedAt"));
        code(call("GET", "/announcements/" + id, null, false), 404, "TARGET_UNAVAILABLE");
        assertEquals("新标题", data(call("POST", "/admin/announcements", input), 201).get("title").stringValue());
        code(call("POST", "/admin/announcements", announcement("冲突", "forum", request)), 409, "DUPLICATE_REQUEST_CONFLICT");
        long newUser = user();
        JsonNode again = data(call("POST", path + "/publish", command(2)), 200);
        assertEquals(published.get("firstPublishedAt"), again.get("firstPublishedAt"));
        assertEquals(receipts, jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE announcement_id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE announcement_id=? AND recipient_id=?", Integer.class, id, newUser));
        data(call("POST", path + "/withdraw", command(3)), 200);
        code(call("GET", "/announcements/" + id, null, false), 404, "TARGET_UNAVAILABLE");
        code(call("POST", path + "/withdraw", command(4)), 409, "STATE_CONFLICT");
        code(call("DELETE", path, null), 428, "PRECONDITION_REQUIRED");
        code(call("DELETE", path, null, true, "If-Match", "\"0\""), 409, "VERSION_CONFLICT");
        assertTrue(data(call("DELETE", path, null, true, "If-Match", "\"4\""), 200).get("deleted").booleanValue());
        code(call("GET", path, null), 404, "TARGET_UNAVAILABLE");
    }

    @Test void publicListMatchesScopeAndBindsCursorToFilter() throws Exception {
        String all = createAndPublish("全范围", "all"), forum = createAndPublish("论坛范围", "forum");
        Set<String> allIds = ids(data(call("GET", "/announcements?scope=all&size=50", null, false), 200));
        assertTrue(allIds.contains(all)); assertFalse(allIds.contains(forum));
        Set<String> forumIds = ids(data(call("GET", "/announcements?scope=forum&size=50", null, false), 200));
        assertTrue(forumIds.containsAll(List.of(all, forum)));
        JsonNode first = data(call("GET", "/announcements?scope=forum&size=1", null, false), 200);
        assertTrue(first.get("hasMore").booleanValue());
        String cursor = first.get("nextCursor").stringValue();
        JsonNode second = data(call("GET", "/announcements?scope=forum&size=1&cursor=" + cursor, null, false), 200);
        assertNotEquals(first.at("/items/0/id"), second.at("/items/0/id"));
        code(call("GET", "/announcements?scope=all&cursor=" + cursor, null, false), 400, "CURSOR_INVALID");
        code(call("GET", "/announcements?scope=bad", null, false), 400, "VALIDATION_FAILED");
    }

    @Test void taskDraftEditPreviewAndSendUseActualReceiptsAndAreReadOnlyAfterSend() throws Exception {
        String request = UUID.randomUUID().toString();
        var input = task("系统通知", "all", request);
        JsonNode draft = data(call("POST", "/admin/notifications", input), 201);
        String id = draft.get("id").stringValue(), path = "/admin/notifications/" + id;
        assertTrue(draft.get("recipientCount").isNull());
        assertFalse(draft.get("externalPush").booleanValue());
        assertEquals("in_app", draft.get("deliveryMode").stringValue());
        data(call("PATCH", path, Map.of("title", "已编辑通知", "body", "完整正文", "audience", "all", "expectedVersion", 0)), 200);
        assertEquals("已编辑通知", data(call("POST", "/admin/notifications", input), 201).get("title").stringValue());
        code(call("POST", "/admin/notifications", task("另一个", "all", request)), 409, "DUPLICATE_REQUEST_CONFLICT");
        JsonNode preview = preview("all");
        code(call("POST", path + "/send", Map.of("expectedVersion", 1, "audienceFingerprint", preview.get("audienceFingerprint").stringValue(), "confirmed", false)), 400, "VALIDATION_FAILED");
        JsonNode sent = data(call("POST", path + "/send", send(1, preview)), 200);
        assertEquals("sent", sent.get("status").stringValue());
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE task_id=?", Integer.class, id);
        assertEquals(preview.get("recipientCount").intValue(), count);
        assertEquals(count, sent.get("recipientCount").intValue());
        JsonNode detail = data(call("GET", path, null), 200);
        assertEquals(count, detail.get("recipientCount").intValue());
        assertFalse(detail.get("externalPush").booleanValue());
        code(call("PATCH", path, Map.of("title", "不能修改", "body", "正文", "audience", "all", "expectedVersion", 2)), 409, "STATE_CONFLICT");
        code(call("POST", path + "/send", send(2, preview)), 409, "STATE_CONFLICT");
    }

    @Test void audiencePreviewUsesEffectiveAccountState() throws Exception {
        long recent = users.getFirst(), restricted = user(), temporary = user(), expired = user(), permanent = user(), deleted = user(), seller = user();
        jdbc.update("UPDATE xl_user SET last_active_at=UTC_TIMESTAMP(3) WHERE id=?", recent);
        jdbc.update("UPDATE xl_user SET restrict_publish=1,last_active_at=UTC_TIMESTAMP(3) WHERE id=?", restricted);
        jdbc.update("UPDATE xl_user SET ban_state='temporary',ban_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY) WHERE id=?", temporary);
        jdbc.update("UPDATE xl_user SET ban_state='temporary',ban_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY),last_active_at=UTC_TIMESTAMP(3) WHERE id=?", expired);
        jdbc.update("UPDATE xl_user SET ban_state='permanent' WHERE id=?", permanent);
        jdbc.update("UPDATE xl_user SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?", deleted);
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE deleted_at IS NULL", Integer.class), preview("all").get("recipientCount").intValue());
        String active = "deleted_at IS NULL AND ban_state<>'permanent' AND NOT(ban_state='temporary' AND ban_until>UTC_TIMESTAMP(3)) AND restrict_publish=0 AND restrict_comment=0 AND restrict_message=0";
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE " + active, Integer.class), preview("active").get("recipientCount").intValue());
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE " + active + " AND last_active_at>=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 30 DAY)", Integer.class), preview("recent").get("recipientCount").intValue());
        assertNotEquals(preview("all").get("audienceFingerprint"), preview("active").get("audienceFingerprint"));
        assertNull(preview("all").get("recipients"));
    }

    @Test void changedAudienceRejectsWithoutReceiptsAndConcurrentSendCommitsOnlyOnce() throws Exception {
        JsonNode draft = data(call("POST", "/admin/notifications", task("并发发送", "all", UUID.randomUUID().toString())), 201);
        String id = draft.get("id").stringValue(), path = "/admin/notifications/" + id;
        JsonNode old = preview("all"); user();
        code(call("POST", path + "/send", send(0, old)), 409, "AUDIENCE_CHANGED");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE task_id=?", Integer.class, id));
        JsonNode fresh = preview("all");
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<HttpResponse<String>> action = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return call("POST", path + "/send", send(0, fresh)); };
            var a = executor.submit(action); var b = executor.submit(action); start.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(a.get(20, TimeUnit.SECONDS).statusCode(), b.get(20, TimeUnit.SECONDS).statusCode()));
            Collections.sort(statuses); assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(fresh.get("recipientCount").intValue(), jdbc.queryForObject("SELECT COUNT(*) FROM xl_user_notification WHERE task_id=?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM xl_notification_task WHERE id=?", Integer.class, id));
    }

    @Test void adminListsHaveIndependentGlobalStatsAndLiteralKeywordMatching() throws Exception {
        data(call("POST", "/admin/announcements", announcement("百分%公告", "all", UUID.randomUUID().toString())), 201);
        JsonNode announcements = data(call("GET", "/admin/announcements?keyword=%25&page=1&pageSize=10", null), 200);
        assertTrue(announcements.get("total").intValue() >= 1);
        for (JsonNode row : announcements.get("items")) assertTrue(row.get("title").stringValue().contains("%") || row.get("body").stringValue().contains("%"));
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM xl_announcement WHERE deleted_at IS NULL", Integer.class), announcements.at("/stats/total").intValue());
        data(call("POST", "/admin/notifications", task("精确全员", "all", UUID.randomUUID().toString())), 201);
        data(call("POST", "/admin/notifications", task("活跃用户", "active", UUID.randomUUID().toString())), 201);
        JsonNode tasks = data(call("GET", "/admin/notifications?audience=all&page=1&pageSize=10", null), 200);
        for (JsonNode row : tasks.get("items")) assertEquals("all", row.get("audience").stringValue());
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM xl_notification_task WHERE audience='all'", Integer.class), tasks.get("total").intValue());
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM xl_notification_task", Integer.class), tasks.at("/stats/total").intValue());
        assertEquals(0, data(call("GET", "/admin/notifications?page=999999&pageSize=10", null), 200).get("items").size());
        code(call("GET", "/admin/notifications?pageSize=11", null), 400, "VALIDATION_FAILED");
        code(call("POST", "/admin/notifications", task("a".repeat(51), "all", UUID.randomUUID().toString())), 400, "VALIDATION_FAILED");
        code(call("GET", "/admin/notifications", null, false), 401, "UNAUTHENTICATED");
    }

    private long user() {
        String open = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id) VALUES ('operations-http-it',?)", open);
        long id = jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='operations-http-it' AND wechat_open_id=?", Long.class, open);
        users.add(id); return id;
    }
    private Map<String, Object> announcement(String title, String scope, String request) { return Map.of("title", title, "body", "真实公告正文", "category", "community", "scope", scope, "clientRequestId", request); }
    private Map<String, Object> task(String title, String audience, String request) { return Map.of("title", title, "body", "完整通知正文", "audience", audience, "clientRequestId", request); }
    private Map<String, Object> command(long version) { return Map.of("expectedVersion", version, "confirmed", true); }
    private Map<String, Object> send(long version, JsonNode preview) { return Map.of("expectedVersion", version, "confirmed", true, "audienceFingerprint", preview.get("audienceFingerprint").stringValue()); }
    private JsonNode preview(String audience) throws Exception { return data(call("POST", "/admin/notifications/audience-preview", Map.of("audience", audience)), 200); }
    private String createAndPublish(String title, String scope) throws Exception {
        String id = data(call("POST", "/admin/announcements", announcement(title, scope, UUID.randomUUID().toString())), 201).get("id").stringValue();
        data(call("POST", "/admin/announcements/" + id + "/publish", command(0)), 200); return id;
    }
    private Set<String> ids(JsonNode page) { Set<String> result = new HashSet<>(); for (JsonNode item : page.get("items")) result.add(item.get("id").stringValue()); return result; }
    private HttpResponse<String> call(String method, String path, Object body) throws Exception { return call(method, path, body, true); }
    private HttpResponse<String> call(String method, String path, Object body, boolean authenticated, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
        if (authenticated) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response, int status) { assertEquals(status, response.statusCode(), response.body()); return json.readTree(response.body()).get("data"); }
    private void code(HttpResponse<String> response, int status, String expected) { assertEquals(status, response.statusCode(), response.body()); assertEquals(expected, json.readTree(response.body()).get("code").stringValue()); }
}
