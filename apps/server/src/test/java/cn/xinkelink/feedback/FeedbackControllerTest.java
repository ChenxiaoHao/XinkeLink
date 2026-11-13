package cn.xinkelink.feedback;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.content.ContentSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackControllerTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ContentSupport support=mock(ContentSupport.class);
    final FeedbackController controller=new FeedbackController(jdbc,support);
    final AuthPrincipal user=new AuthPrincipal(7,9,"user");
    @Test void submissionRejectsBlankAndConflictingRetries() {
        when(support.require(user,null)).thenReturn(7L);
        assertThrows(BusinessException.class,()->controller.submit(new FeedbackController.Input("  ","request-1"),user));
        when(jdbc.queryForMap(anyString(),eq(7L),eq("request-1"))).thenReturn(Map.of("id",1L,"content","原内容"));
        assertThrows(BusinessException.class,()->controller.submit(new FeedbackController.Input("不同内容","request-1"),user));
    }
    @Test void adminAuthorizationIsRequiredBeforeReading() {
        doThrow(new BusinessException(cn.xinkelink.common.api.ErrorCode.FORBIDDEN)).when(support).requireAdmin(user);
        assertThrows(BusinessException.class,()->controller.list(1,10,user));
        verifyNoInteractions(jdbc);
    }
}
