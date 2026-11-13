package cn.xinkelink.profile;

import cn.xinkelink.auth.*;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.content.ContentSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Map;

@RestController @Profile("auth") @RequestMapping("/api/v1/me")
public class ProfileController {
    private final ProfileService service;
    public ProfileController(ProfileService service) {this.service=service;}
    @PatchMapping ApiResponse<AuthModels.Me> update(@RequestBody JsonNode body,@AuthenticationPrincipal AuthPrincipal principal) {return ApiResponse.success(service.update(body,principal));}
    @GetMapping("/preferences") ApiResponse<Map<String,Object>> preferences(@AuthenticationPrincipal AuthPrincipal principal) {return ApiResponse.success(service.preferences(principal));}
    @PatchMapping("/preferences") ApiResponse<Map<String,Object>> preferences(@RequestBody JsonNode body,@AuthenticationPrincipal AuthPrincipal principal) {return ApiResponse.success(service.preferences(body,principal));}
    @PutMapping("/blocks/{userId}") ApiResponse<Map<String,Object>> block(@PathVariable String userId,@AuthenticationPrincipal AuthPrincipal principal) {return ApiResponse.success(service.block(ContentSupport.id(userId),true,principal));}
    @DeleteMapping("/blocks/{userId}") ApiResponse<Map<String,Object>> unblock(@PathVariable String userId,@AuthenticationPrincipal AuthPrincipal principal) {return ApiResponse.success(service.block(ContentSupport.id(userId),false,principal));}
}
