package cn.xinkelink.forum;

import cn.xinkelink.forum.metrics.PostMetricsCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in real HTTP benchmark; not discovered by ordinary *Test/*IT suites.
 * Stores only timings, counts and test conditions, never response bodies or identities. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "server.tomcat.threads.max=16"})
@ActiveProfiles({"db", "auth"})
@Import(PostMetricsPerformanceBenchmark.Instrumentation.class)
class PostMetricsPerformanceBenchmark {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PostMetricsCache cache;
    @Autowired MeterRegistry meters;
    static final Probe PROBE = new Probe();
    static final Pattern COUNTS = Pattern.compile("\"likeCount\":10,\"commentCount\":10,\"liked\":false,\"favorited\":false,\"isMine\":false");
    final List<Long> posts = new ArrayList<>();
    final String marker = "perf" + UUID.randomUUID().toString().replace("-", "");
    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostMetricsPerformanceBenchmark::isolatedUrl);
        registry.add("spring.flyway.url", PostMetricsPerformanceBenchmark::isolatedUrl);
        registry.add("xinkelink.auth.jwt.secret-base64", () -> System.getenv("XINKELINK_JWT_SECRET_BASE64"));
        registry.add("xinkelink.cache.post-metrics.enabled", () -> "true");
        registry.add("xinkelink.cache.post-metrics.l1.enabled", () -> "true");
    }
    static String isolatedUrl() {
        String url = System.getenv("XINKELINK_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_[a-zA-Z0-9_]+_test(?:\\?.*)?"))
            throw new IllegalStateException("Benchmark requires existing isolated cache test DB on13306");
        return url;
    }

    @Test void compareIdenticalGuestHttpAcrossThreeModes() throws Exception {
        assertEquals("PONG", redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping()));
        fixture();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (String initialMode : List.of("database", "redis", "caffeine_redis")) {
                mode(initialMode); clear();
                run("/api/v1/posts/" + posts.getFirst(), "detail", 400, 4, false);
                run("/api/v1/posts?sort=latest&size=20&keyword=" + marker, "list", 400, 4, false);
            }
            // Three rotated rounds suppress simple fixed-order JVM/OS warmup bias.
            for (int round = 0; round < 3; round++) {
                List<String> modes = new ArrayList<>(List.of("database", "redis", "caffeine_redis"));
                Collections.rotate(modes, -round % modes.size());
                for (String mode : modes) {
                    mode(mode); clear();
                    for (String route : List.of("detail", "list")) {
                        String path = route.equals("detail") ? "/api/v1/posts/" + posts.getFirst()
                                : "/api/v1/posts?sort=latest&size=20&keyword=" + marker;
                        for (int concurrency : List.of(1, 4)) {
                            run(path, route, 80, concurrency, false); // same warmup in every mode
                            results.add(measure(mode, route, "hot", round, path, 120, concurrency, false));
                        }
                        if (round == 0) results.add(measure(mode, route, "cold", round, path, 40, 1, true));
                    }
                }
            }
            String report = json(Map.of("cacheClassSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    PostMetricsCache.class.getResourceAsStream("PostMetricsCache.class").readAllBytes())), "conditions", Map.of(
                    "identity", "guest", "routes", "one post and exactly20 representative posts; each10likes10comments; no images",
                    "server", "same JVM/build, random local TCP port, JDK21, root WARN, MySQL13306, WSL Redis16379",
                    "sampling", "global400requests per route/mode before sampling;three rotated rounds; each120samples after80warmups;concurrency1and4;40cold per route/mode",
                    "coldThroughput", "wall throughput includes per-request explicit cache eviction; HTTP latency excludes eviction",
                    "timings", "HTTP includes body read and generic count validation; Service wraps actual service proxy including transaction; JDBC timings count execute calls; MyBatis includes mapping",
                    "scope", "synthetic moderate dataset in preexisting dedicated test DB only; not production capacity or significance proof"), "results", results));
            Path output = Path.of(System.getProperty("post.metrics.perf.output", "../../.local/metrics-fence-20261115/perf-http-final.json"));
            Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, report);
            System.out.println("POST_METRICS_BENCHMARK rows=" + results.size() + " output=" + output.toAbsolutePath().normalize());
            for (var row : results) { var compact = new LinkedHashMap<>(row); compact.remove("rawMs"); System.out.println("POST_METRICS_BENCHMARK " + json(compact)); }
            assertEquals(0L, results.stream().mapToLong(r -> ((Number) r.get("failures")).longValue()).sum());
        } finally {
            mode("caffeine_redis"); clear();
            for (Long id : posts) jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?", id);
        }
    }

    void redisRoundTripDiagnostics() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        String prefix = "xinkelink:perf:" + UUID.randomUUID() + ":";
        for (int size : List.of(1, 20)) {
            List<String> keys = java.util.stream.IntStream.range(0, size).mapToObj(i -> prefix + i).toList();
            Map<String, List<Double>> timings = new LinkedHashMap<>();
            for (String kind : List.of("mget", "sequential_set", "pipeline_set", "connection_pipeline_set")) timings.put(kind, new ArrayList<>());
            try {
                for (int iteration = 0; iteration < 230; iteration++) {
                    List<String> order = new ArrayList<>(timings.keySet()); if (iteration % 2 == 0) Collections.reverse(order);
                    for (String kind : order) {
                        long start = System.nanoTime();
                        if (kind.equals("mget")) redis.opsForValue().multiGet(keys);
                        else if (kind.equals("sequential_set")) for (String key : keys) redis.opsForValue().set(key, "1:2", Duration.ofSeconds(30));
                        else if (kind.equals("pipeline_set")) redis.executePipelined(new SessionCallback<Object>() {
                            @Override @SuppressWarnings("unchecked") public <K, V> Object execute(RedisOperations<K, V> operations) {
                                RedisOperations<String, String> strings = (RedisOperations<String, String>) operations;
                                for (String key : keys) strings.opsForValue().set(key, "1:2", Duration.ofSeconds(30));
                                return null;
                            }
                        });
                        else redis.executePipelined((RedisCallback<Object>) connection -> {
                            for (String key : keys) connection.stringCommands().set(key.getBytes(StandardCharsets.UTF_8),
                                    "1:2".getBytes(StandardCharsets.UTF_8), Expiration.seconds(30), SetOption.UPSERT);
                            return null;
                        });
                        if (iteration >= 30) timings.get(kind).add((System.nanoTime() - start) / 1e6);
                    }
                }
                for (var entry : timings.entrySet()) {
                    var sorted = entry.getValue().stream().sorted().toList();
                    rows.add(Map.of("batch", size, "operation", entry.getKey(), "samples", sorted.size(), "meanMs", sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                            "p50Ms", sorted.get(99), "p95Ms", sorted.get(189), "rawMs", entry.getValue()));
                }
            } finally { redis.delete(keys); }
        }
        Path output = Path.of("../../.local/l1-cache-20261115/perf-template-redis.json");
        Files.writeString(output, json(Map.of("conditions", "same Spring StringRedisTemplate used by cache;30warmup200measured alternating order persistent Lettuce; synthetic owned prefix30secTTL explicit cleanup; no HTTP load", "results", rows)));
    }

    void fixture() {
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String open = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('metrics-perf',?,'隔离性能用户')", open);
            users.add(jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='metrics-perf' AND wechat_open_id=?", Long.class, open));
        }
        for (int i = 0; i < 20; i++) {
            String request = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO xl_post(author_id,category,content,client_request_id,request_hash) VALUES(?,'campus',?,?,UNHEX(SHA2(?,256)))",
                    users.getFirst(), marker, request, request);
            long id = jdbc.queryForObject("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?", Long.class, users.getFirst(), request);
            posts.add(id);
            for (int j = 1; j <= 10; j++) {
                jdbc.update("INSERT INTO xl_like(user_id,target_type,target_id) VALUES(?,'post',?)", users.get(j), id);
                String comment = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO xl_comment(target_type,target_id,author_id,content,client_request_id,request_hash) VALUES('post',?,?,'隔离性能评论',?,UNHEX(SHA2(?,256)))",
                        id, users.get(j), comment, comment);
            }
        }
    }
    void mode(String mode) {
        ReflectionTestUtils.setField(cache, "enabled", !mode.equals("database"));
        ReflectionTestUtils.setField(cache, "l1Enabled", mode.equals("caffeine_redis"));
    }
    void clear() { cache.invalidateAfterCommit(posts); }
    Map<String, Object> measure(String mode, String route, String temperature, int round,
            String path, int samples, int concurrency, boolean cold) throws Exception {
        var before = snapshot(); PROBE.reset();
        long start = System.nanoTime(); var observed = run(path, route, samples, concurrency, cold);
        double seconds = (System.nanoTime() - start) / 1e9;
        var counters = delta(snapshot(), before);
        List<Double> sorted = observed.elapsed.stream().sorted().toList();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mode", mode); row.put("route", route); row.put("temperature", temperature); row.put("round", round);
        row.put("concurrency", concurrency); row.put("samples", samples); row.put("failures", observed.failures);
        row.put("meanMs", sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        row.put("p50Ms", sorted.get((int) Math.ceil(samples * .50) - 1)); row.put("p95Ms", sorted.get((int) Math.ceil(samples * .95) - 1));
        row.put("throughputPerSec", samples / seconds); row.put("cache", counters); row.put("segments", PROBE.snapshot());
        row.put("rawMs", observed.elapsed);
        return row;
    }
    record Observed(List<Double> elapsed, long failures) {}
    Observed run(String path, String route, int samples, int concurrency, boolean cold) throws Exception {
        List<Double> elapsed = Collections.synchronizedList(new ArrayList<>()); LongAdder failures = new LongAdder();
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < concurrency; worker++) {
                final int count = samples / concurrency + (worker < samples % concurrency ? 1 : 0);
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < count; i++) {
                        if (cold) clear();
                        long start = System.nanoTime();
                        try {
                            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                            long items = COUNTS.matcher(response.body()).results().count();
                            if (response.statusCode() != 200 || items != (route.equals("detail") ? 1 : 20)) failures.increment();
                        } catch (Exception failure) { failures.increment(); }
                        elapsed.add((System.nanoTime() - start) / 1e6);
                    }
                }));
            }
            for (Future<?> future : futures) future.get();
        }
        return new Observed(elapsed, failures.sum());
    }
    Map<String, Double> snapshot() {
        Map<String, Double> values = new TreeMap<>();
        for (var meter : meters.getMeters()) {
            if (!meter.getId().getName().startsWith("post.metrics.")) continue;
            for (var measure : meter.measure()) {
                if (!Set.of("COUNT", "TOTAL_TIME").contains(measure.getStatistic().name())) continue;
                String key = meter.getId().getName() + meter.getId().getTags() + "." + measure.getStatistic().name();
                values.put(key, measure.getValue());
            }
        }
        return values;
    }
    static Map<String, Double> delta(Map<String, Double> after, Map<String, Double> before) {
        Map<String, Double> delta = new TreeMap<>(); after.forEach((k, v) -> delta.put(k, v - before.getOrDefault(k, 0.0))); return delta;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Instrumentation {
        @Bean SqlProbe sqlProbe() { return new SqlProbe(); }
        @Bean static BeanPostProcessor metricsPerformanceProbes() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof ForumService) {
                        ProxyFactory proxy = new ProxyFactory(bean); proxy.setProxyTargetClass(true);
                        proxy.addAdvice((MethodInterceptor) invocation -> {
                            boolean measured = Set.of("list", "detail").contains(invocation.getMethod().getName());
                            long start = System.nanoTime();
                            try { return invocation.proceed(); }
                            finally { if (measured) PROBE.record("service", System.nanoTime() - start); }
                        }); return proxy.getProxy();
                    }
                    if (bean instanceof DataSource dataSource && name.equals("dataSource")) return new DelegatingDataSource(dataSource) {
                        @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
                        @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(super.getConnection(user, password)); }
                    };
                    return bean;
                }
            };
        }
    }
    static Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
            Object value = invoke(connection, method, args);
            if (value instanceof PreparedStatement statement) return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                if (!m.getName().startsWith("execute")) return invoke(statement, m, a);
                long start = System.nanoTime();
                try { return invoke(statement, m, a); }
                finally { PROBE.record("jdbc." + SqlProbe.CATEGORY.get(), System.nanoTime() - start); }
            });
            return value;
        });
    }
    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    @Intercepts(@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class SqlProbe implements Interceptor {
        static final ThreadLocal<String> CATEGORY = ThreadLocal.withInitial(() -> "other");
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            String category = id.endsWith(".publicPostMetrics") ? "public" : id.endsWith(".postViewerStates") ? "viewer" : "other";
            CATEGORY.set(category); long start = System.nanoTime();
            try { return invocation.proceed(); }
            finally { PROBE.record("mapper." + category, System.nanoTime() - start); CATEGORY.remove(); }
        }
    }
    static class Probe {
        final Map<String, LongAdder> count = new ConcurrentHashMap<>(), nanos = new ConcurrentHashMap<>();
        void record(String key, long elapsed) { count.computeIfAbsent(key, k -> new LongAdder()).increment(); nanos.computeIfAbsent(key, k -> new LongAdder()).add(elapsed); }
        void reset() { count.clear(); nanos.clear(); }
        Map<String, Object> snapshot() {
            Map<String, Object> result = new TreeMap<>(); count.forEach((key, value) -> result.put(key, Map.of("calls", value.sum(), "totalMs", nanos.get(key).sum()/1e6)));
            return result;
        }
    }
    // Tiny JSON writer to keep the diagnostic independent of application JSON settings.
    static String json(Object value) {
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream().map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof Collection<?> list) return "[" + String.join(",", list.stream().map(PostMetricsPerformanceBenchmark::json).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
