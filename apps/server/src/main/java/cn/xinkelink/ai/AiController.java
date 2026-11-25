package cn.xinkelink.ai;
import cn.xinkelink.auth.AuthPrincipal;import cn.xinkelink.common.api.*;
import org.springframework.context.annotation.Profile;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @Profile("auth") @RequestMapping("/api/v1/ai/sessions")
public class AiController {
 private final AiSessionService service;public AiController(AiSessionService service){this.service=service;}
 @GetMapping ApiResponse<?> list(@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.list(viewer));}
 @PostMapping ApiResponse<?> create(@RequestBody(required=false) Map<String,String> body,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.create(viewer,body==null?null:body.get("title")));}
 @GetMapping("/{id}") ApiResponse<?> history(@PathVariable String id,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.history(id,viewer));}
 @DeleteMapping("/{id}") ApiResponse<?> delete(@PathVariable String id,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.delete(id,viewer));}
 @PostMapping("/{id}/messages") ApiResponse<?> send(@PathVariable String id,@RequestBody Map<String,String> body,@AuthenticationPrincipal AuthPrincipal viewer){if(!body.keySet().equals(java.util.Set.of("requestId","text")))throw new BusinessException(ErrorCode.VALIDATION_FAILED);return ApiResponse.success(service.send(id,body.get("requestId"),body.get("text"),viewer));}
}
