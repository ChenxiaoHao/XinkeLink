package cn.xinkelink.operations;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.notification.NotificationService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static cn.xinkelink.operations.OperationsModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 空全局受众用存储边界夹具，避免测试修改开发库其他账号。 */
class OperationsPolicyTest {
    private final OperationsRepository repository=mock(OperationsRepository.class);
    private final ContentSupport support=mock(ContentSupport.class);
    private final NotificationTaskService service=new NotificationTaskService(repository,support,mock(NotificationService.class));
    private final AuthPrincipal admin=new AuthPrincipal(1,1,"admin");
    private final Instant now=Instant.parse("2026-10-25T00:00:00Z");

    @Test void emptyAudienceHasExplicitRejectionInsteadOfPretendingTaskWasSent() {
        when(support.requireAdmin(admin)).thenReturn(1L);
        when(repository.now()).thenReturn(now);
        when(repository.task(1,true)).thenReturn(new TaskRow(1,"通知","正文","all","draft",1,1,new byte[32],null,null,now,now,0));
        when(repository.audienceUsers(true)).thenReturn(List.of());
        BusinessException error=assertThrows(BusinessException.class,()->service.send(1,new SendTask(0L,"0".repeat(64),true),admin));
        assertEquals("NO_RECIPIENTS",error.code().name());
    }
    @Test void unconfirmedSendAndMissingAudienceAreRejected() {
        when(support.requireAdmin(admin)).thenReturn(1L);
        assertEquals("VALIDATION_FAILED",assertThrows(BusinessException.class,()->service.send(1,new SendTask(0L,"0".repeat(64),false),admin)).code().name());
        assertEquals("VALIDATION_FAILED",assertThrows(BusinessException.class,()->service.preview(null,admin)).code().name());
    }
    @Test void expiredBanKeepsManualRestrictionsAndThirtyDayBoundaryIsInclusive() {
        when(support.requireAdmin(admin)).thenReturn(1L);
        when(repository.now()).thenReturn(now);
        when(repository.audienceUsers(false)).thenReturn(List.of(
                new AudienceUser(1,"temporary",now.minusSeconds(1),false,false,false,now.minusSeconds(30*86400L)),
                new AudienceUser(2,"temporary",now.minusSeconds(1),false,true,false,now),
                new AudienceUser(3,"none",null,false,false,false,now.minusSeconds(30*86400L+1)),
                new AudienceUser(4,"permanent",null,false,false,false,now)));
        assertEquals(2,service.preview("active",admin).get("recipientCount"));
        assertEquals(1,service.preview("recent",admin).get("recipientCount"));
        assertEquals(4,service.preview("all",admin).get("recipientCount"));
    }
}
