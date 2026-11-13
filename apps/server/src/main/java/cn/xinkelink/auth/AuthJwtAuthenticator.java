package cn.xinkelink.auth;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

public class AuthJwtAuthenticator {
    private final AuthService service;
    private final java.util.function.LongConsumer expiredBans;
    public AuthJwtAuthenticator(AuthService service) { this(service,id->{}); }
    public AuthJwtAuthenticator(AuthService service,java.util.function.LongConsumer expiredBans) { this.service=service;this.expiredBans=expiredBans; }

    public UsernamePasswordAuthenticationToken authenticate(Jwt jwt) {
        try {
            // 在认证只读事务前规范过期临时封禁，保留独立人工限制。
            if("user".equals(jwt.getClaimAsString("actorType")))expiredBans.accept(Long.parseLong(jwt.getSubject()));
            AuthPrincipal principal = service.authenticate(jwt);
            return new UsernamePasswordAuthenticationToken(principal, "",
                    List.of(new SimpleGrantedAuthority(principal.actorType().equals("admin") ? "ROLE_ADMIN" : "ROLE_USER")));
        } catch (BusinessException exception) { throw rejected(exception.code()); }
        catch (DataAccessException exception) { throw rejected(ErrorCode.SERVICE_UNAVAILABLE); }
    }

    private OAuth2AuthenticationException rejected(ErrorCode code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code.name(), code.message(), null));
    }
}
