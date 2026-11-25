package cn.xinkelink.ai;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.ai.knowledge.KnowledgeService;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@Profile("auth")
@RequestMapping("/api/v1/admin/ai/service")
public class AiAdminController {
    public record ServiceState(boolean enabled, long revision, List<AiRuntime.ModelView> models) {}
    public record Dashboard(ServiceState settings, AiUsage.Totals today, List<AiUsage.Event> records) {}
    public record Toggle(Boolean enabled, Long revision) {}
    public record ModelInput(String model, String baseUrl, String apiKey, Long revision) {
        @Override public String toString() { return "AI model update"; }
    }

    private final ContentSupport support;
    private final AiRuntime runtime;
    private final AiUsage usage;
    private final AiGateway gateway;
    private final KnowledgeService knowledge;
    private final ReentrantLock editor = new ReentrantLock();

    public AiAdminController(ContentSupport support, AiRuntime runtime, AiUsage usage, KnowledgeService knowledge, AiGateway gateway) {
        this.support = support;
        this.runtime = runtime;
        this.usage = usage;
        this.knowledge = knowledge;
        this.gateway = gateway;
    }

    @GetMapping
    public ApiResponse<Dashboard> dashboard(@AuthenticationPrincipal AuthPrincipal admin) {
        support.requireAdmin(admin);
        return ApiResponse.success(new Dashboard(settings(), usage.today(), usage.recent()));
    }

    @PutMapping("/state")
    public ApiResponse<ServiceState> toggle(@RequestBody Toggle input, @AuthenticationPrincipal AuthPrincipal admin) {
        support.requireAdmin(admin);
        if (input.enabled() == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        beginEdit();
        try {
            var current = version(input.revision());
            if (current.enabled() == input.enabled()) return ApiResponse.success(settings());
            var next = new AiRuntime.State(input.enabled(), current.revision() + 1, current.overrides());
            runtime.write(next, current.revision());
            runtime.use(next);
            usage.change(next.enabled() ? "enable" : "disable", null, null);
            return ApiResponse.success(settings());
        } finally { editor.unlock(); }
    }

    @PutMapping("/models/{stage}")
    public ApiResponse<ServiceState> edit(@PathVariable String stage, @RequestBody ModelInput input,
                                          @AuthenticationPrincipal AuthPrincipal admin) {
        support.requireAdmin(admin);
        var target = AiRuntime.Stage.parse(stage);
        beginEdit();
        try {
            var current = version(input.revision());
            var next = runtime.editProvider(current, target, input.model(), input.baseUrl(), input.apiKey());
            var provider = runtime.provider(next, target);
            var previous = runtime.view(current, target);
            boolean endpointChanged = !provider.model().equals(previous.model()) || !provider.baseUrl().equals(previous.baseUrl());
            boolean replacedKey = input.apiKey() != null && !input.apiKey().isBlank();
            if (target == AiRuntime.Stage.EMBEDDING && endpointChanged) {
                knowledge.reindex(provider, () -> runtime.write(next, current.revision()), () -> runtime.use(next));
            } else {
                if (current.enabled() && (endpointChanged || replacedKey)) {
                    try { gateway.check(target, provider); }
                    catch (BusinessException failure) { throw new BusinessException(ErrorCode.AI_CONNECTION_FAILED); }
                }
                runtime.write(next, current.revision());
                runtime.use(next);
            }
            usage.change("model", target, provider.model());
            return ApiResponse.success(settings());
        } finally { editor.unlock(); }
    }

    private void beginEdit() {
        if (!editor.tryLock()) throw new BusinessException(ErrorCode.AI_CONFIGURATION_BUSY);
    }

    private AiRuntime.State version(Long clientRevision) {
        var state = runtime.state();
        if (clientRevision == null || clientRevision != state.revision()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        return state;
    }

    private ServiceState settings() {
        var state = runtime.state();
        return new ServiceState(state.enabled(), state.revision(), runtime.models(state));
    }
}
