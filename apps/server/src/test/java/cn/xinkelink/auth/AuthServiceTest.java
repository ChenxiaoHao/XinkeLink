package cn.xinkelink.auth;

import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.auth.persistence.AuthSession;
import cn.xinkelink.auth.persistence.UserAccount;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    @Test
    void crossingTheSigningDeadlineDuringRefreshReturnsAnAuthenticationError() {
        Instant now = Instant.parse("2026-10-24T12:00:00.200Z");
        AuthMapper mapper = mock(AuthMapper.class);
        JwtTokenService jwt = mock(JwtTokenService.class);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now, now.plusMillis(300), now.plusMillis(900));
        AuthService service = new AuthService(mapper, jwt, mock(PasswordEncoder.class), clock);
        AuthSession session = new AuthSession(1, "user", 1, new byte[32], 0, null, now.plusMillis(1600), null, null, 0);
        when(mapper.findSessionByRefreshHashForUpdate(any())).thenReturn(session);
        when(mapper.findUser(1)).thenReturn(new UserAccount(1, "同学", null, "", "", "", null, "none", null, false, false, false, null, 0));
        when(mapper.rotateSession(anyLong(), any(), any(), isNull(), any())).thenReturn(1);
        when(jwt.issue(anyString(), anyLong(), anyLong(), any())).thenThrow(new IllegalArgumentException("会话已过期"));
        BusinessException error = assertThrows(BusinessException.class, () -> service.refreshUser("test-ticket"));
        assertEquals(ErrorCode.REFRESH_INVALID, error.code());
    }

    @Test
    void lastFractionOfASecondCannotRotateIntoAnAlreadyExpiredJwt() {
        Instant now = Instant.parse("2026-10-24T12:00:00.500Z");
        AuthMapper mapper = mock(AuthMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        JwtTokenService jwt = mock(JwtTokenService.class);
        AuthService service = new AuthService(mapper, jwt, passwords, Clock.fixed(now, ZoneOffset.UTC));
        AuthSession session = new AuthSession(1, "user", 1, new byte[32], 0, null, now.plusMillis(200), null, null, 0);
        when(mapper.findSessionByRefreshHashForUpdate(any())).thenReturn(session);
        BusinessException error = assertThrows(BusinessException.class, () -> service.refreshUser("test-ticket"));
        assertEquals(ErrorCode.REFRESH_INVALID, error.code());
        verify(mapper, never()).rotateSession(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(jwt);
    }
}
