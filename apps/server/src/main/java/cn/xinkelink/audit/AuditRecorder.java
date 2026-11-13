package cn.xinkelink.audit;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.common.api.BusinessException;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Service @Profile("auth")
public class AuditRecorder {
    private final JdbcTemplate jdbc;
    private final AuthMapper auth;
    private final JsonMapper json;
    private final TransactionTemplate independent;
    private static final Map<String,String> TABLES=Map.of("post","xl_post","comment","xl_comment",
            "user","xl_user","report","xl_report","announcement","xl_announcement","notification","xl_notification_task");
    public AuditRecorder(JdbcTemplate jdbc,AuthMapper auth,JsonMapper json,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.auth=auth;this.json=json;independent=new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(3);
    }
    public Object invoke(MethodInvocation call,AuditAction rule) throws Throwable {
        AuthPrincipal actor=Arrays.stream(call.getArguments()).filter(AuthPrincipal.class::isInstance)
                .map(AuthPrincipal.class::cast).filter(p->"admin".equals(p.actorType())).findFirst().orElse(null);
        Context context=prepare(actor,call.getArguments(),rule);
        try(var scope=AuditChanges.begin()) {
            Object result=call.proceed();
            if(context!=null && (scope.changed()!=null?scope.changed():changed(context,result))) record(context,"success","OK",result);
            return result;
        } catch(Throwable failure) {
            if(context!=null) record(context,failure instanceof BusinessException?"business_rejected":"execution_error",
                    failure instanceof BusinessException business?business.code().name():"INTERNAL_ERROR",null);
            throw failure;
        }
    }
    private Context prepare(AuthPrincipal actor,Object[] arguments,AuditAction rule) {
        try {
            if(actor==null)return null;
            var account=auth.findAdmin(actor.subjectId());
            if(account==null||!"active".equals(account.status())||!"ADMIN".equals(account.role()))return null;
            Long id=arguments.length>0&&arguments[0] instanceof Long number?number:null;
            String table=TABLES.get(rule.targetType());Long before=id==null||table==null?null:version(table,id);
            // 创建重试使用同一幂等键；只判断是否已存在，绝不把键或请求值写入日志。
            boolean existed=false;
            if(id==null && Set.of("announcements","notifications").contains(rule.module())) {
                for(Object argument:arguments) if(argument!=null) try {
                    Object key=argument.getClass().getMethod("clientRequestId").invoke(argument);
                    String createTable=rule.module().equals("announcements")?"xl_announcement":"xl_notification_task";
                    existed=jdbc.queryForObject("SELECT COUNT(*) FROM "+createTable+" WHERE created_by=? AND client_request_id=?",Integer.class,actor.subjectId(),key)>0;
                } catch(ReflectiveOperationException ignored) { /* 非创建参数不包含幂等键。 */ }
            }
            return new Context(UUID.randomUUID().toString(),actor.subjectId(),account.displayName(),rule,id,before,existed);
        } catch(RuntimeException failure) { unavailable();return null; }
    }
    private Long version(String table,long id) {
        var versions=jdbc.queryForList("SELECT version FROM "+table+" WHERE id=?",Long.class,id);
        return versions.isEmpty()?null:versions.getFirst();
    }
    private boolean changed(Context context,Object result) {
        try {
            if(context.existed())return false;
            if(result instanceof Map<?,?> values) {
                if(values.get("changedCount") instanceof Number count && count.intValue()==0)return false;
                if(values.get("changedFields") instanceof Collection<?> fields && fields.isEmpty())return false;
            }
            if(context.id()!=null && context.before()!=null) {
                Long after=version(TABLES.get(context.rule().targetType()),context.id());
                return !Objects.equals(context.before(),after);
            }
            return true;
        } catch(RuntimeException failure) { unavailable();return false; }
    }
    private void record(Context context,String outcome,String code,Object result) {
        Runnable write=()->safeWrite(context,outcome,code,result);
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            // 有外层事务时，成功日志只能跟随最终提交；回滚不得留下成功记录。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if(!"success".equals(outcome)||status==STATUS_COMMITTED)write.run();
                }
            });
        } else write.run();
    }
    private void safeWrite(Context context,String outcome,String code,Object result) {
        try {
            Long target=context.id();int count="success".equals(outcome)?1:0;
            List<String> fields="success".equals(outcome)?AuditPolicy.fields(List.of(context.rule().fields())):List.of();
            if(result instanceof Map<?,?> values) {
                if(target==null && values.get("id") instanceof String id && id.matches("[1-9][0-9]{0,18}"))target=Long.valueOf(id);
                if(values.get("changedCount") instanceof Number amount)count=amount.intValue();
                if(values.get("changedFields") instanceof Collection<?> changed)fields=AuditPolicy.fields(changed.stream().map(String::valueOf).toList());
            }
            Long id=target;int changedCount=count;String encoded=json.writeValueAsString(fields);
            independent.executeWithoutResult(status->jdbc.update("""
                    INSERT INTO xl_audit_log(operation_id,actor_type,admin_id,actor_display,module,action,target_type,
                    target_internal_id,target_count,outcome,summary_code,result_code,changed_fields)
                    VALUES (?,'admin',?,?,?,?,?,?,?,?,?,?,?)
                    """,context.operation(),context.admin(),context.name(),context.rule().module(),context.rule().action(),
                    context.rule().targetType(),id,changedCount,outcome,context.rule().action(),code,encoded));
        } catch(RuntimeException failure) { unavailable(); }
    }
    private void unavailable() { LoggerFactory.getLogger(AuditRecorder.class).warn("审计追加失败，原管理操作结果保持不变"); }
    private record Context(String operation,long admin,String name,AuditAction rule,Long id,Long before,boolean existed) {}
}
