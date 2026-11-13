package cn.xinkelink.notification;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.CursorPage;
import cn.xinkelink.content.ContentSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile("auth")
@RequestMapping("/api/v1")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service){this.service=service;}
    public record MarkAll(String throughNotificationId){}
    @GetMapping("/notifications")
    ApiResponse<CursorPage<Map<String,Object>>> list(@RequestParam(required=false) String type,
            @RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer size,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.list(type,unreadOnly,cursor,size,viewer));}
    @PutMapping("/notifications/{notificationId}/read")
    ApiResponse<Map<String,Object>> read(@PathVariable String notificationId,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.read(ContentSupport.id(notificationId),viewer));}
    @PostMapping("/me/notifications/read")
    ApiResponse<Map<String,Object>> readAll(@RequestBody MarkAll input,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.readAll(input.throughNotificationId(),viewer));}
    @GetMapping("/me/unread")
    ApiResponse<Map<String,Object>> unread(@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.unread(viewer));}
}
