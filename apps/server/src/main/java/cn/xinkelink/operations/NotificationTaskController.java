package cn.xinkelink.operations;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.content.ContentSupport;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.xinkelink.operations.OperationsModels.*;

@RestController
@Profile("auth")
public class NotificationTaskController {
    private final NotificationTaskService service;
    public NotificationTaskController(NotificationTaskService service) { this.service=service; }
    @GetMapping("/api/v1/admin/notifications") @Operation(summary="管理站内通知任务及实际收件统计")
    ApiResponse<Map<String,Object>> list(@RequestParam(required=false) String keyword,@RequestParam(required=false) String audience,
            @RequestParam(required=false) String status,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer pageSize,
            @AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.list(keyword,audience,status,page,pageSize,viewer)); }
    @GetMapping("/api/v1/admin/notifications/{notificationId}") @Operation(summary="管理站内通知任务详情")
    ApiResponse<Map<String,Object>> detail(@PathVariable String notificationId,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.detail(ContentSupport.id(notificationId),viewer)); }
    @PostMapping("/api/v1/admin/notifications") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="创建站内通知草稿")
    ApiResponse<Map<String,Object>> create(@Valid @RequestBody CreateTask input,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.create(input,viewer)); }
    @PatchMapping("/api/v1/admin/notifications/{notificationId}") @Operation(summary="编辑未发送站内通知草稿")
    ApiResponse<Map<String,Object>> edit(@PathVariable String notificationId,@Valid @RequestBody EditTask input,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.edit(ContentSupport.id(notificationId),input,viewer)); }
    @PostMapping("/api/v1/admin/notifications/audience-preview") @Operation(summary="预览精确受众人数与指纹")
    ApiResponse<Map<String,Object>> preview(@Valid @RequestBody AudiencePreview input,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.preview(input.audience(),viewer)); }
    @PostMapping("/api/v1/admin/notifications/{notificationId}/send") @Operation(summary="确认创建站内收件记录")
    ApiResponse<Map<String,Object>> send(@PathVariable String notificationId,@Valid @RequestBody SendTask input,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.send(ContentSupport.id(notificationId),input,viewer)); }
}
