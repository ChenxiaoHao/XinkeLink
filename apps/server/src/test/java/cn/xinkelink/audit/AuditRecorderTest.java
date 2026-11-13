package cn.xinkelink.audit;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.persistence.*;
import cn.xinkelink.common.api.*;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditRecorderTest {
    static class Command {
        @AuditAction(module="posts",action="edit",targetType="post",fields={"password","status"})
        public Object execute(Object request,AuthPrincipal admin) {return request;}
    }
    @Test void brokenAuditStorageCannotChangeSuccessfulBusinessResult() throws Throwable {
        var invocation=call();Object result=Map.of("changedCount",1);when(invocation.proceed()).thenReturn(result);
        assertSame(result,recorder().invoke(invocation,annotation()));
    }
    @Test void brokenAuditStorageCannotReplaceBusinessRejection() throws Throwable {
        var invocation=call();var expected=new BusinessException(ErrorCode.VERSION_CONFLICT);when(invocation.proceed()).thenThrow(expected);
        assertSame(expected,assertThrows(BusinessException.class,()->recorder().invoke(invocation,annotation())));
    }
    @Test void brokenAuditStorageCannotReplaceExecutionFailure() throws Throwable {
        var invocation=call();var expected=new IllegalStateException("private detail");when(invocation.proceed()).thenThrow(expected);
        assertSame(expected,assertThrows(IllegalStateException.class,()->recorder().invoke(invocation,annotation())));
    }
    private AuditAction annotation() throws Exception {return Command.class.getMethod("execute",Object.class,AuthPrincipal.class).getAnnotation(AuditAction.class);}
    private MethodInvocation call() {
        var invocation=mock(MethodInvocation.class);when(invocation.getArguments()).thenReturn(new Object[]{Map.of("password","not-for-audit"),new AuthPrincipal(7,8,"admin")});return invocation;
    }
    private AuditRecorder recorder() {
        var auth=mock(AuthMapper.class);when(auth.findAdmin(7)).thenReturn(new AdminAccount(7,"test-admin","hash","测试管理员","ADMIN","active",Instant.now(),null));
        var jdbc=mock(JdbcTemplate.class);doThrow(new IllegalStateException("storage down")).when(jdbc).update(anyString(),any(Object[].class));
        return new AuditRecorder(jdbc,auth,JsonMapper.builder().build(),mock(PlatformTransactionManager.class));
    }
}
