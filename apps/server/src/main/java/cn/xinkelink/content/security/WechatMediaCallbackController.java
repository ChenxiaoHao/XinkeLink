package cn.xinkelink.content.security;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * 微信消息推送回调端点（用于接收 wxa_media_check 图片异步审核结果）。
 */
@RestController
@Profile("auth")
@RequestMapping("/api/v1/webhooks/wechat/media-check")
public class WechatMediaCallbackController {
    private final WechatMediaReviewService reviewService;

    public WechatMediaCallbackController(WechatMediaReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam("signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {
        if (!reviewService.verifySignature(signature, timestamp, nonce)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("invalid signature");
        }
        return ResponseEntity.ok(echostr);
    }

    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> callback(
            @RequestParam("signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody(required = false) JsonNode payload) {
        if (!reviewService.verifySignature(signature, timestamp, nonce)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("invalid signature");
        }
        if (payload != null && payload.isObject()
                && "wxa_media_check".equals(payload.path("Event").asString(""))) {
            String traceId = payload.path("trace_id").asString("");
            String suggest = payload.path("result").path("suggest").asString("");
            reviewService.handleCallback(traceId, suggest);
        }
        return ResponseEntity.ok("success");
    }
}
