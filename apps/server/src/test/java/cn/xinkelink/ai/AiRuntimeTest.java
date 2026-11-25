package cn.xinkelink.ai;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiRuntimeTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AiRuntime settings = new AiRuntime(jdbc, new MockEnvironment()
            .withProperty("xinkelink.auth.jwt.secret-base64", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .withProperty("xinkelink.ai.chat.model", "qwen3.7-flash-2026-07-15")
            .withProperty("xinkelink.ai.chat.key", "test-only-original-key")
            .withProperty("xinkelink.ai.chat.url", "https://dashscope.aliyuncs.com/compatible-mode/v1")
            .withProperty("xinkelink.ai.allowed-hosts", "model.example.com"));

    @Test
    void replacesAndEncryptsKeyWithoutExposingItInTheAdminResponse() {
        var original = settings.state();
        var next = settings.editProvider(original, AiRuntime.Stage.CHAT, "test-model",
                "https://model.example.com/v1", "test-only-replacement");
        assertEquals("test-only-replacement", settings.provider(next, AiRuntime.Stage.CHAT).key());
        assertFalse(next.toString().contains("test-only-replacement"));
        settings.use(next);
        String response = JsonMapper.builder().build().writeValueAsString(settings.models());
        assertFalse(response.contains("test-only-replacement"));
        assertFalse(response.contains("test-only-original-key"));
        assertTrue(settings.models().getFirst().keyConfigured());
    }

    @Test
    void keepsKeysWithinTheirProviderAndRejectsAnUntrustedUrl() {
        var original = settings.state();
        var next = settings.editProvider(original, AiRuntime.Stage.CHAT, "test-model",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/", "");
        assertEquals("test-only-original-key", settings.provider(next, AiRuntime.Stage.CHAT).key());
        assertThrows(BusinessException.class, () -> settings.editProvider(original, AiRuntime.Stage.CHAT,
                "test-model", "https://model.example.com/v1", ""));
        assertThrows(BusinessException.class, () -> settings.editProvider(original, AiRuntime.Stage.CHAT,
                "test-model", "https://127.0.0.1/v1", "test-key"));
    }

    @Test
    void disabledServiceRejectsCallsBeforeResolvingCredentials() {
        settings.use(new AiRuntime.State(false, 1, Map.of()));
        assertEquals(ErrorCode.AI_DISABLED, assertThrows(BusinessException.class, settings::requireEnabled).code());
    }
}
