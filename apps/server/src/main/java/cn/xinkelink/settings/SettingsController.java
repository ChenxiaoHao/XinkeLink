package cn.xinkelink.settings;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Profile("auth") @RequestMapping("/api/v1/admin/settings")
public class SettingsController {
    private final SettingsService service;
    public SettingsController(SettingsService service) {this.service=service;}
    @GetMapping ApiResponse<Map<String,Object>> read(@AuthenticationPrincipal AuthPrincipal principal) {return ApiResponse.success(service.read(principal));}
    @PatchMapping("/content-review") ApiResponse<Map<String,Object>> save(@Valid @RequestBody SettingsModels.Review input,@AuthenticationPrincipal AuthPrincipal principal) {return ApiResponse.success(service.save(input,principal));}
    @PostMapping("/content-review/reset") ApiResponse<Map<String,Object>> reset(@Valid @RequestBody SettingsModels.Reset input,@AuthenticationPrincipal AuthPrincipal principal) {return ApiResponse.success(service.reset(input,principal));}
}
