package cn.xinkelink.audit;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.content.ContentSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController @Profile("auth") @RequestMapping("/api/v1/admin/audit-events")
public class AuditController {
    private final AuditQueryService service;
    public AuditController(AuditQueryService service) {this.service=service;}
    @GetMapping ApiResponse<Map<String,Object>> list(@RequestParam(required=false) String keyword,@RequestParam(required=false) String actorKeyword,
            @RequestParam(required=false) String adminId,@RequestParam(required=false) String module,@RequestParam(required=false) String action,
            @RequestParam(required=false) String outcome,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) Integer page,@RequestParam(required=false) Integer pageSize,@AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.list(new AuditQueryService.Filter(keyword,actorKeyword,adminId,module,action,outcome,from,to),page,pageSize,viewer));
    }
    @GetMapping("/{eventId}") ApiResponse<Map<String,Object>> detail(@PathVariable String eventId,@AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.detail(ContentSupport.id(eventId),viewer));
    }
    @GetMapping("/export") ResponseEntity<byte[]> export(@RequestParam(required=false) String keyword,@RequestParam(required=false) String actorKeyword,
            @RequestParam(required=false) String adminId,@RequestParam(required=false) String module,@RequestParam(required=false) String action,
            @RequestParam(required=false) String outcome,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @AuthenticationPrincipal AuthPrincipal viewer) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=xinkelink-audit.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(service.export(new AuditQueryService.Filter(keyword,actorKeyword,adminId,module,action,outcome,from,to),viewer));
    }
}
