package cn.xinkelink.profile;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.auth.persistence.UserAccount;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.media.MediaService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileDeactivateTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void rejectsMissingOrFalseConfirmation() throws Exception {
        ContentSupport support = mock(ContentSupport.class);
        when(support.require(any(), isNull())).thenReturn(11L);
        ProfileService service = new ProfileService(
                mock(JdbcTemplate.class), support, mock(AuthMapper.class), mock(AuthService.class), mock(MediaService.class));

        AuthPrincipal actor = new AuthPrincipal(11L, 1L, "user");
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> service.deactivate(JSON.readTree("{\"confirmed\":false}"), actor));
        assertEquals(ErrorCode.VALIDATION_FAILED, ex1.code());

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> service.deactivate(JSON.readTree("{}"), actor));
        assertEquals(ErrorCode.VALIDATION_FAILED, ex2.code());
    }

    @Test
    void softDeletesUserDetachesAvatarAndRevokesAllSessions() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContentSupport support = mock(ContentSupport.class);
        AuthMapper mapper = mock(AuthMapper.class);
        MediaService media = mock(MediaService.class);
        AuthPrincipal actor = new AuthPrincipal(22L, 7L, "user");

        when(support.require(actor, null)).thenReturn(22L);
        when(mapper.findUser(22L)).thenReturn(new UserAccount(
                22L, "小陈", 99L, "简介", "软件工程", "1班", "2024", "none", null, false, false, false, null, 3L));

        ProfileService service = new ProfileService(jdbc, support, mapper, mock(AuthService.class), media);
        var result = service.deactivate(JSON.readTree("{\"confirmed\":true}"), actor);

        assertEquals(true, result.get("deactivated"));
        verify(media).bindAvatar(22L, null);
        verify(jdbc).update(contains("UPDATE xl_user"), eq(22L));
        verify(jdbc).update(contains("UPDATE xl_auth_session"), eq(22L));
    }
}
