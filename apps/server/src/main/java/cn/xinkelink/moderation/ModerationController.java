package cn.xinkelink.moderation;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.content.ContentSupport;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@Profile("auth")
public class ModerationController {
    private final ReportModerationService reports;
    private final UserModerationService users;
    public ModerationController(ReportModerationService reports,UserModerationService users){this.reports=reports;this.users=users;}
    @PostMapping("/api/v1/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="提交帖子、评论或用户举报")
    ApiResponse<Map<String,Object>> create(@RequestBody ModerationModels.CreateReport body,@AuthenticationPrincipal AuthPrincipal principal){return ApiResponse.success(reports.create(body,principal));}
    @GetMapping("/api/v1/admin/reports")
    @Operation(summary="管理员举报队列")
    ApiResponse<Map<String,Object>> list(@RequestParam(required=false)String keyword,@RequestParam(required=false)String type,
            @RequestParam(required=false)String priority,@RequestParam(required=false)String status,@RequestParam(required=false)Instant from,
            @RequestParam(required=false)Instant to,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer pageSize,
            @AuthenticationPrincipal AuthPrincipal principal){return ApiResponse.success(reports.list(keyword,type,priority,status,from,to,page,pageSize,principal));}
    @GetMapping("/api/v1/admin/reports/{reportId}")
    @Operation(summary="管理员案件详情和保全证据")
    ApiResponse<Map<String,Object>> detail(@PathVariable String reportId,@AuthenticationPrincipal AuthPrincipal principal){return ApiResponse.success(reports.detail(ContentSupport.id(reportId),principal));}
    @PostMapping("/api/v1/admin/reports/{reportId}/resolution")
    @Operation(summary="管理员一次性处置举报")
    ApiResponse<Map<String,Object>> resolve(@PathVariable String reportId,@RequestBody ModerationModels.Resolution body,@AuthenticationPrincipal AuthPrincipal principal){return ApiResponse.success(reports.resolve(ContentSupport.id(reportId),body,principal));}
    @PostMapping("/api/v1/admin/users/{userId}/restrictions")
    @Operation(summary="管理员设置独立通道限制")
    ApiResponse<Map<String,Object>> restrictions(@PathVariable String userId,@RequestBody ModerationModels.Restrictions body,@AuthenticationPrincipal AuthPrincipal principal){return ApiResponse.success(users.restrictions(ContentSupport.id(userId),body,principal));}
    @PostMapping("/api/v1/admin/users/{userId}/ban")
    @Operation(summary="管理员临时或永久封禁用户")
    ApiResponse<Map<String,Object>> ban(@PathVariable String userId,@RequestBody ModerationModels.Ban body,@AuthenticationPrincipal AuthPrincipal principal){return ApiResponse.success(users.ban(ContentSupport.id(userId),body,principal));}
    @PostMapping("/api/v1/admin/users/{userId}/release")
    @Operation(summary="管理员解除人工限制与临时封禁")
    ApiResponse<Map<String,Object>> release(@PathVariable String userId,@RequestBody ModerationModels.Release body,@AuthenticationPrincipal AuthPrincipal principal){return ApiResponse.success(users.release(ContentSupport.id(userId),body,principal));}
}
