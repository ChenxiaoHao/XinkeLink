package cn.xinkelink.ai;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Runtime overrides for the three provider connections. Only encrypted keys reach MySQL. */
@Component
@Profile("auth")
public class AiRuntime {
    public enum Stage {
        CHAT, EMBEDDING, RERANK;

        public String id() { return name().toLowerCase(Locale.ROOT); }

        static Stage parse(String value) {
            return Arrays.stream(values()).filter(stage -> stage.id().equals(value)).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_RANGE));
        }
    }

    public record Provider(String model, String baseUrl, String key, Boolean thinking, String protocol) {
        @Override public String toString() { return "AI provider " + model; }
    }

    public record ModelView(String stage, String model, String baseUrl, boolean keyConfigured) {}
    record SavedModel(String model, String baseUrl, String encryptedKey) {}
    record State(boolean enabled, long revision, Map<Stage, SavedModel> overrides) {}

    private static final int NONCE_BYTES = 12;
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile State current = new State(true, 0, Map.of());

    public AiRuntime(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.env = env;
    }

    @PostConstruct
    void load() {
        current = jdbc.queryForObject("SELECT enabled, revision, model_settings FROM xl_ai_runtime WHERE id=1", (row, index) -> {
            var root = json.readTree(row.getString("model_settings"));
            var overrides = new EnumMap<Stage, SavedModel>(Stage.class);
            for (Stage stage : Stage.values()) {
                if (root.hasNonNull(stage.id())) {
                    var entry = root.path(stage.id());
                    overrides.put(stage, new SavedModel(entry.path("model").asText(), entry.path("baseUrl").asText(),
                            entry.hasNonNull("encryptedKey") ? entry.path("encryptedKey").asText() : null));
                }
            }
            return new State(row.getBoolean("enabled"), row.getLong("revision"), Map.copyOf(overrides));
        });
    }

    State state() { return current; }
    void use(State state) { current = state; }

    void write(State state, long expected) {
        var settings = new java.util.LinkedHashMap<String, SavedModel>();
        state.overrides().forEach((stage, values) -> settings.put(stage.id(), values));
        if (jdbc.update("UPDATE xl_ai_runtime SET enabled=?, revision=?, model_settings=? WHERE id=1 AND revision=?",
                state.enabled(), state.revision(), json.writeValueAsString(settings), expected) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    public void requireEnabled() {
        if (!current.enabled()) throw new BusinessException(ErrorCode.AI_DISABLED);
    }

    public Provider provider(Stage stage) { return provider(current, stage); }

    static Provider defaults(Environment env, Stage stage) {
        String prefix = "xinkelink.ai." + stage.id() + ".";
        return new Provider(env.getProperty(prefix + "model", ""), normalizeBase(env.getProperty(prefix + "url", "")),
                env.getProperty(prefix + "key", ""), stage == Stage.CHAT ? env.getProperty(prefix + "enable-thinking", Boolean.class) : null,
                env.getProperty("xinkelink.ai.rerank.protocol", "cohere"));
    }

    Provider provider(State state, Stage stage) {
        Provider fallback = defaults(env, stage);
        SavedModel override = state.overrides().get(stage);
        if (override == null) return fallback;
        String key = override.encryptedKey() == null
                ? (sameOrigin(fallback.baseUrl(), override.baseUrl()) ? fallback.key() : "")
                : secret(Cipher.DECRYPT_MODE, stage, override.baseUrl(), override.encryptedKey());
        Boolean thinking = stage == Stage.CHAT && (override.model().equals(fallback.model())
                || override.baseUrl().startsWith("https://dashscope.aliyuncs.com/")
                && override.model().matches("qwen3\\.[567]-flash(?:-[0-9-]+)?")) ? fallback.thinking() : null;
        String protocol = stage == Stage.RERANK && !override.baseUrl().equals(fallback.baseUrl())
                ? (override.baseUrl().endsWith("/services/rerank/text-rerank/text-rerank") ? "dashscope" : "cohere")
                : fallback.protocol();
        return new Provider(override.model(), override.baseUrl(), key, thinking, protocol);
    }

    List<ModelView> models() { return models(current); }

    List<ModelView> models(State state) {
        return Arrays.stream(Stage.values()).map(stage -> view(state, stage)).toList();
    }

    ModelView view(State state, Stage stage) {
        var saved = state.overrides().get(stage);
        var fallback = defaults(env, stage);
        if (saved == null) {
            return new ModelView(stage.id(), fallback.model(), redactedUrl(fallback.baseUrl()), !fallback.key().isBlank());
        }
        boolean configured = saved.encryptedKey() != null
                || sameOrigin(fallback.baseUrl(), saved.baseUrl()) && !fallback.key().isBlank();
        return new ModelView(stage.id(), saved.model(), saved.baseUrl(), configured);
    }

    State editProvider(State previous, Stage stage, String model, String baseUrl, String key) {
        if (model == null || !model.strip().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}")) {
            throw new BusinessException(ErrorCode.AI_MODEL_INVALID);
        }
        String base = validateBase(stage, baseUrl);
        String value = key == null ? "" : key.strip();
        if (value.length() > 1024 || value.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new BusinessException(ErrorCode.AI_KEY_INVALID);
        }
        SavedModel old = previous.overrides().get(stage);
        String encrypted;
        if (!value.isEmpty()) {
            encrypted = secret(Cipher.ENCRYPT_MODE, stage, base, value);
        } else {
            var prior = provider(previous, stage);
            if (!sameOrigin(prior.baseUrl(), base) || prior.key().isBlank()) {
                throw new BusinessException(ErrorCode.AI_KEY_REQUIRED);
            }
            encrypted = old == null || old.encryptedKey() == null ? null
                    : (prior.baseUrl().equals(base) ? old.encryptedKey() : secret(Cipher.ENCRYPT_MODE, stage, base, prior.key()));
        }
        var overrides = new EnumMap<Stage, SavedModel>(Stage.class);
        overrides.putAll(previous.overrides());
        overrides.put(stage, new SavedModel(model.strip(), base, encrypted));
        return new State(previous.enabled(), previous.revision() + 1, Map.copyOf(overrides));
    }

    private String validateBase(Stage stage, String input) {
        String value = normalizeBase(input == null ? "" : input);
        try {
            URI url = URI.create(value);
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
            String original = defaults(env, stage).baseUrl();
            // Exact, operator-provided trust exceptions also support existing mock providers.
            if (!original.isBlank() && value.length() <= 500 && original.equals(value) && url.getUserInfo() == null
                    && url.getQuery() == null && url.getFragment() == null && !host.isBlank()) return value;
            boolean allowed = host.equals("dashscope.aliyuncs.com")
                    || Arrays.stream(env.getProperty("xinkelink.ai.allowed-hosts", "").split(","))
                    .map(String::strip).filter(item -> !item.isEmpty()).anyMatch(host::equalsIgnoreCase);
            if (value.length() <= 500 && "https".equalsIgnoreCase(url.getScheme()) && allowed
                    && (url.getPort() == -1 || url.getPort() == 443)
                    && url.getUserInfo() == null && url.getQuery() == null && url.getFragment() == null
                    && url.normalize().getRawPath().equals(url.getRawPath())) return value;
        } catch (IllegalArgumentException ignored) {
            // Never include the raw address in an error response.
        }
        throw new BusinessException(ErrorCode.AI_BASE_URL_INVALID);
    }

    private static String normalizeBase(String value) { return value.strip().replaceAll("/+$", ""); }

    private static String redactedUrl(String value) {
        try {
            URI url = URI.create(value);
            if (url.getUserInfo() == null && url.getQuery() == null && url.getFragment() == null) return value;
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    private static boolean sameOrigin(String first, String second) {
        if (first.isBlank() || second.isBlank()) return false;
        try {
            URI a = URI.create(first), b = URI.create(second);
            return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                    && effectivePort(a) == effectivePort(b);
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static int effectivePort(URI url) {
        return url.getPort() != -1 ? url.getPort() : ("https".equalsIgnoreCase(url.getScheme()) ? 443 : 80);
    }

    private String secret(int mode, Stage stage, String url, String value) {
        try {
            String configured = env.getProperty("xinkelink.ai.settings-key", "");
            byte[] source = Base64.getDecoder().decode(configured.isBlank() ?
                    env.getRequiredProperty("xinkelink.auth.jwt.secret-base64") : configured);
            if (source.length < 32) throw new IllegalArgumentException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(source, "HmacSHA256"));
            var aes = new SecretKeySpec(mac.doFinal("xinkelink/ai-provider/v1".getBytes(StandardCharsets.UTF_8)), "AES");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] bytes = mode == Cipher.ENCRYPT_MODE ? value.getBytes(StandardCharsets.UTF_8) : Base64.getDecoder().decode(value);
            byte[] nonce = new byte[NONCE_BYTES];
            if (mode == Cipher.ENCRYPT_MODE) random.nextBytes(nonce);
            else System.arraycopy(bytes, 0, nonce, 0, NONCE_BYTES);
            cipher.init(mode, aes, new GCMParameterSpec(128, nonce));
            cipher.updateAAD((stage.id() + "\n" + url).getBytes(StandardCharsets.UTF_8));
            if (mode == Cipher.DECRYPT_MODE) {
                return new String(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES), StandardCharsets.UTF_8);
            }
            byte[] encrypted = cipher.doFinal(bytes);
            byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception ignored) {
            throw new BusinessException(ErrorCode.AI_SETTINGS_KEY_INVALID);
        }
    }
}
