package cn.xinkelink.operations;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
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
public class AnnouncementController {
    private final AnnouncementService service;
    public AnnouncementController(AnnouncementService service) { this.service=service; }

    @GetMapping("/api/v1/announcements") @Operation(summary="公开公告列表")
    ApiResponse<CursorPage<Map<String,Object>>> list(@RequestParam String scope,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer size,@AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.list(scope,cursor,size,viewer));
    }
    @GetMapping("/api/v1/announcements/{announcementId}") @Operation(summary="公开公告详情")
    ApiResponse<Map<String,Object>> detail(@PathVariable String announcementId) { return ApiResponse.success(service.detail(ContentSupport.id(announcementId))); }
    @GetMapping("/api/v1/admin/announcements") @Operation(summary="管理公告列表及状态统计")
    ApiResponse<Map<String,Object>> adminList(@RequestParam(required=false) String keyword,@RequestParam(required=false) String category,
            @RequestParam(required=false) String status,@RequestParam(required=false) String scope,@RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer pageSize,@AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.adminList(keyword,category,status,scope,page,pageSize,viewer));
    }
    @GetMapping("/api/v1/admin/announcements/{announcementId}") @Operation(summary="管理公告详情")
    ApiResponse<Map<String,Object>> adminDetail(@PathVariable String announcementId,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.adminDetail(ContentSupport.id(announcementId),viewer)); }
    @PostMapping("/api/v1/admin/announcements") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="创建公告草稿")
    ApiResponse<Map<String,Object>> create(@Valid @RequestBody CreateAnnouncement input,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.create(input,viewer)); }
    @PatchMapping("/api/v1/admin/announcements/{announcementId}") @Operation(summary="保存公告编辑为草稿")
    ApiResponse<Map<String,Object>> edit(@PathVariable String announcementId,@Valid @RequestBody EditAnnouncement input,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.edit(ContentSupport.id(announcementId),input,viewer)); }
    @PostMapping("/api/v1/admin/announcements/{announcementId}/publish") @Operation(summary="确认发布公告")
    ApiResponse<Map<String,Object>> publish(@PathVariable String announcementId,@Valid @RequestBody ConfirmCommand input,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.publish(ContentSupport.id(announcementId),input,viewer)); }
    @PostMapping("/api/v1/admin/announcements/{announcementId}/withdraw") @Operation(summary="确认撤回公告")
    ApiResponse<Map<String,Object>> withdraw(@PathVariable String announcementId,@Valid @RequestBody ConfirmCommand input,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.withdraw(ContentSupport.id(announcementId),input,viewer)); }
    @DeleteMapping("/api/v1/admin/announcements/{announcementId}") @Operation(summary="删除公告")
    ApiResponse<Map<String,Object>> delete(@PathVariable String announcementId,@RequestHeader(value="If-Match",required=false) String version,@AuthenticationPrincipal AuthPrincipal viewer) { return ApiResponse.success(service.delete(ContentSupport.id(announcementId),version,viewer)); }
}
