package cn.xinkelink.auth;

import cn.xinkelink.auth.ratelimit.AuthRateLimiter;
import cn.xinkelink.auth.wechat.WechatCodeClient;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

import static cn.xinkelink.auth.AuthModels.*;

@RestController
@Profile("auth")
public class AuthController {
    private final AuthService service;
    private final WechatCodeClient wechat;
    private final AuthRateLimiter limiter;
    private final AdminBrowserSecurity browser;
    public AuthController(AuthService service, WechatCodeClient wechat, AuthRateLimiter limiter, AdminBrowserSecurity browser) {
        this.service = service; this.wechat = wechat; this.limiter = limiter; this.browser = browser;
    }

    @PostMapping("/api/v1/auth/wechat")
    ApiResponse<UserTokens> wechat(@Valid @RequestBody WechatLogin input, HttpServletRequest request) {
        limiter.check("wechat", request.getRemoteAddr());
        return ApiResponse.success(service.loginUser(wechat.exchange(input.code())));
    }

    @PostMapping("/api/v1/auth/refresh")
    ApiResponse<UserTokens> refresh(@Valid @RequestBody Refresh input, HttpServletRequest request) {
        limiter.check("refresh", request.getRemoteAddr());
        return ApiResponse.success(service.refreshUser(input.refreshToken()));
    }

    @PostMapping("/api/v1/auth/logout")
    @SecurityRequirement(name = "bearerAuth")
    ApiResponse<Logout> logout(@AuthenticationPrincipal AuthPrincipal principal) {
        service.logoutUser(principal); return ApiResponse.success(new Logout(true));
    }

    @GetMapping("/api/v1/me")
    @SecurityRequirement(name = "bearerAuth")
    ApiResponse<Me> me(@AuthenticationPrincipal AuthPrincipal principal) { return ApiResponse.success(service.currentUser(principal)); }

    @PostMapping("/api/v1/admin/auth/login")
    ApiResponse<AdminTokens> adminLogin(@Valid @RequestBody AdminLogin input, @RequestHeader(value = "Origin", required = false) String origin,
            HttpServletRequest request, HttpServletResponse response) {
        browser.checkOrigin(origin);
        limiter.check("admin", "ip:" + request.getRemoteAddr());
        limiter.check("admin", "account:" + input.account().strip().toLowerCase(Locale.ROOT));
        AdminSession session = service.loginAdmin(input.account(), input.password());
        browser.setCookie(response, session.refreshToken(), session.expiresAt());
        return ApiResponse.success(session.tokens());
    }

    @PostMapping("/api/v1/admin/auth/refresh")
    ApiResponse<AdminTokens> adminRefresh(@CookieValue(value = AdminBrowserSecurity.COOKIE, required = false) String refresh,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrf, HttpServletRequest request, HttpServletResponse response) {
        browser.checkOrigin(origin);
        limiter.check("refresh", request.getRemoteAddr());
        AdminSession session = service.refreshAdmin(refresh, csrf);
        // 只有成功响应写Cookie，避免并发旧票据的失败响应覆盖刚轮换的新票据。
        browser.setCookie(response, session.refreshToken(), session.expiresAt());
        return ApiResponse.success(session.tokens());
    }

    @PostMapping("/api/v1/admin/auth/logout")
    ApiResponse<Logout> adminLogout(@CookieValue(value = AdminBrowserSecurity.COOKIE, required = false) String refresh,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrf, HttpServletResponse response) {
        browser.checkOrigin(origin);
        try {
            service.logoutAdmin(refresh, csrf);
            browser.clearCookie(response);
            return ApiResponse.success(new Logout(true));
        } catch (BusinessException exception) {
            if (exception.code() == ErrorCode.REFRESH_INVALID) browser.clearCookie(response);
            throw exception;
        }
    }

    @GetMapping("/api/v1/admin/me")
    @SecurityRequirement(name = "bearerAuth")
    ApiResponse<AdminMe> adminMe(@AuthenticationPrincipal AuthPrincipal principal) { return ApiResponse.success(service.currentAdmin(principal)); }
}
