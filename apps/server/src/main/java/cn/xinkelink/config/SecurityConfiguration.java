package cn.xinkelink.config;

import cn.xinkelink.auth.AuthJwtAuthenticator;
import cn.xinkelink.auth.AdminBrowserSecurity;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private static final Set<String> AUTH_PUBLIC = Set.of("/api/v1/auth/wechat", "/api/v1/auth/refresh",
            "/api/v1/admin/auth/login", "/api/v1/admin/auth/refresh", "/api/v1/admin/auth/logout");

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JsonMapper mapper,
            ObjectProvider<JwtTokenService> tokens, ObjectProvider<AuthJwtAuthenticator> authenticators,
            ObjectProvider<AdminBrowserSecurity> browsers) {
        boolean authEnabled = tokens.getIfAvailable() != null;
        AuthenticationEntryPoint entry = (request, response, exception) -> {
            // 过期Bearer会先于Controller失败，退出响应仍需清除受信后台的刷新Cookie。
            if (path(request).equals("/api/v1/admin/auth/logout") && request.getMethod().equals("POST")) {
                browsers.ifAvailable(browser -> browser.clearLogoutCookie(request.getHeader("Origin"), response));
            }
            write(response, mapper, api(request) ? authenticationError(exception) : ErrorCode.NOT_FOUND);
        };
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // Bearer不依赖浏览器Cookie；后台刷新/退出另行严格检查Origin和会话绑定CSRF。
                .csrf(csrf -> {
                    if (authEnabled) csrf.ignoringRequestMatchers(request -> AUTH_PUBLIC.contains(path(request))
                            || path(request).equals("/api/v1/auth/logout")
                            || statisticsPost(request) || bearer(request));
                })
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/v1/health", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    if (authEnabled) auth.requestMatchers(AUTH_PUBLIC.toArray(String[]::new)).permitAll();
                    if (authEnabled) auth.requestMatchers(HttpMethod.POST,"/api/v1/statistics/anonymous-credential","/api/v1/statistics/post-views").permitAll();
                    auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                    if (authEnabled) auth.requestMatchers(HttpMethod.GET,"/api/v1/posts","/api/v1/posts/*",
                            "/api/v1/posts/*/comments","/api/v1/comments/*/replies",
                            "/api/v1/users/*","/api/v1/users/*/posts","/api/v1/search",
                            "/api/v1/images/*/access","/api/v1/images/*/content").permitAll();
                    if(authEnabled)auth.requestMatchers(HttpMethod.GET,"/api/v1/announcements","/api/v1/announcements/*").permitAll();
                    if (authEnabled) auth.requestMatchers("/api/**").hasRole("USER");
                    else auth.requestMatchers("/api/**").authenticated();
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entry)
                        .accessDeniedHandler((request, response, exception) -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            ErrorCode code = authentication == null || authentication instanceof AnonymousAuthenticationToken
                                    ? ErrorCode.UNAUTHENTICATED : ErrorCode.FORBIDDEN;
                            write(response, mapper, api(request) ? code : ErrorCode.NOT_FOUND);
                        }));
        if (authEnabled) http.oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.decoder(tokens.getObject())
                        .jwtAuthenticationConverter(authenticators.getObject()::authenticate))
                .authenticationEntryPoint(entry));
        return http.build();
    }

    private static String path(HttpServletRequest request) { return request.getRequestURI().substring(request.getContextPath().length()); }
    private static boolean statisticsPost(HttpServletRequest request) { return "POST".equals(request.getMethod()) && Set.of("/api/v1/statistics/anonymous-credential","/api/v1/statistics/post-views").contains(path(request)); }
    private static boolean api(HttpServletRequest request) { return path(request).startsWith("/api/"); }
    private static boolean bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static ErrorCode authenticationError(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validation && validation.getErrors().stream()
                    .anyMatch(error -> error.getErrorCode().equals("TOKEN_EXPIRED"))) return ErrorCode.TOKEN_EXPIRED;
            if (cause instanceof OAuth2AuthenticationException oauth) {
                if (oauth.getError().getErrorCode().equals("ACCOUNT_BANNED")) return ErrorCode.ACCOUNT_BANNED;
                if (oauth.getError().getErrorCode().equals("SERVICE_UNAVAILABLE")) return ErrorCode.SERVICE_UNAVAILABLE;
            }
        }
        return ErrorCode.UNAUTHENTICATED;
    }

    private void write(HttpServletResponse response, JsonMapper mapper, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.failure(code));
    }
}
