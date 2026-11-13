package cn.xinkelink.beta;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.ratelimit.AuthRateLimiter;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static cn.xinkelink.beta.BetaModels.*;

@RestController @Profile("auth")
public class BetaController {
    private final BetaService beta;
    private final AuthRateLimiter limiter;
    public BetaController(BetaService beta,AuthRateLimiter limiter){this.beta=beta;this.limiter=limiter;}
    @GetMapping("/api/v1/beta/access") ApiResponse<Access> access(@AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(beta.access(actor));}
    @PostMapping("/api/v1/beta/redeem") ApiResponse<Access> redeem(@AuthenticationPrincipal AuthPrincipal actor,@RequestBody Redeem input,HttpServletRequest request){
        if(actor==null||!"user".equals(actor.actorType()))throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        limiter.check("beta","ip:"+request.getRemoteAddr());limiter.check("beta","account:"+actor.subjectId());
        return ApiResponse.success(beta.redeem(actor,input.code()));
    }
    @GetMapping("/api/v1/admin/beta") ApiResponse<Management> management(@AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(beta.management(actor));}
    @PostMapping("/api/v1/admin/beta/initialize") ApiResponse<Management> initialize(@AuthenticationPrincipal AuthPrincipal actor){return ApiResponse.success(beta.initialize(actor));}
    @PatchMapping("/api/v1/admin/beta") ApiResponse<Management> change(@AuthenticationPrincipal AuthPrincipal actor,@Valid @RequestBody ChangePhase input){return ApiResponse.success(beta.changePhase(actor,input));}
}
