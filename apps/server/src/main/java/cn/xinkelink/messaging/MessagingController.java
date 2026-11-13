package cn.xinkelink.messaging;

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

@RestController
@Profile("auth")
public class MessagingController {
    private final MessagingService service;
    public MessagingController(MessagingService service){this.service=service;}
    @GetMapping("/api/v1/conversations")
    @Operation(summary="本人的会话列表")
    ApiResponse<CursorPage<Map<String,Object>>> list(@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer size,
            @AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(service.list(cursor,size,actor));}
    @PostMapping("/api/v1/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="打开唯一的双方会话")
    ApiResponse<Map<String,Object>> open(@Valid @RequestBody MessagingModels.OpenConversation body,@AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(service.open(body,actor));}
    @GetMapping("/api/v1/conversations/{conversationId}")
    @Operation(summary="本人成员会话详情")
    ApiResponse<Map<String,Object>> detail(@PathVariable String conversationId,@AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(service.detail(ContentSupport.id(conversationId),actor));}
    @GetMapping("/api/v1/conversations/{conversationId}/messages")
    @Operation(summary="消息历史、补取、搜索和定位")
    ApiResponse<Map<String,Object>> history(@PathVariable String conversationId,@RequestParam(required=false) String beforeSequence,
            @RequestParam(required=false) String afterSequence,@RequestParam(required=false) String aroundMessageId,
            @RequestParam(required=false) Integer size,@RequestParam(required=false) String q,@AuthenticationPrincipal AuthPrincipal actor){
        return ApiResponse.success(service.history(ContentSupport.id(conversationId),beforeSequence,afterSequence,aroundMessageId,size,q,actor));
    }
    @PostMapping("/api/v1/conversations/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="持久化发送文字消息")
    ApiResponse<Map<String,Object>> send(@PathVariable String conversationId,@Valid @RequestBody MessagingModels.SendMessage body,
            @AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(service.send(ContentSupport.id(conversationId),body,actor));}
    @PutMapping("/api/v1/conversations/{conversationId}/read")
    @Operation(summary="推进本人成员已读水位")
    ApiResponse<Map<String,Object>> read(@PathVariable String conversationId,@Valid @RequestBody MessagingModels.Read body,
            @AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(service.read(ContentSupport.id(conversationId),body,actor));}
    @PostMapping("/api/v1/me/conversations/read")
    @Operation(summary="按捕获的水位全部已读")
    ApiResponse<Map<String,Object>> readAll(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthPrincipal actor){
        if(!body.isEmpty())throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return ApiResponse.success(service.readAll(actor));
    }
    @GetMapping("/api/v1/conversations/{conversationId}/settings")
    @Operation(summary="本人的会话偏好")
    ApiResponse<Map<String,Object>> settings(@PathVariable String conversationId,@AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(service.settings(ContentSupport.id(conversationId),actor));}
    @PatchMapping("/api/v1/conversations/{conversationId}/settings")
    @Operation(summary="保存本人的会话偏好")
    ApiResponse<Map<String,Object>> settings(@PathVariable String conversationId,@Valid @RequestBody MessagingModels.Settings body,
            @AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(service.settings(ContentSupport.id(conversationId),body,actor));}
    @PostMapping("/api/v1/conversations/{conversationId}/clear")
    @Operation(summary="按水位清空本人的历史")
    ApiResponse<Map<String,Object>> clear(@PathVariable String conversationId,@Valid @RequestBody MessagingModels.Clear body,
            @AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(service.clear(ContentSupport.id(conversationId),body,actor));}
}
