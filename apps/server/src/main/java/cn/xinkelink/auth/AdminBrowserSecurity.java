package cn.xinkelink.auth;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class AdminBrowserSecurity {
    public static final String COOKIE = "xinkelink_admin_refresh";
    private static final String PATH = "/api/v1/admin/auth";
    private final AdminSecurityProperties properties;
    private final Clock clock;
    public AdminBrowserSecurity(AdminSecurityProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }

    public void checkOrigin(String origin) {
        if (origin == null || !properties.allowedOrigins().contains(origin)) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    public void setCookie(HttpServletResponse response, String token, Instant expires) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, Duration.between(clock.instant(), expires)).toString());
    }

    public void clearCookie(HttpServletResponse response) { response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()); }

    public void clearLogoutCookie(String origin, HttpServletResponse response) {
        if (origin != null && properties.allowedOrigins().contains(origin)) clearCookie(response);
    }

    private ResponseCookie cookie(String token, Duration lifetime) {
        return ResponseCookie.from(COOKIE, token).path(PATH).secure(true).httpOnly(true).sameSite("Strict").maxAge(lifetime).build();
    }
}
