package cn.xinkelink.moderation;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.notification.NotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

import static cn.xinkelink.content.ContentSupport.map;
import static cn.xinkelink.moderation.ModerationRepository.*;

@Service
@Profile("auth")
public class UserModerationService {
    private final ModerationRepository repository;
    private final ContentSupport support;
    private final BanExpiryService expiry;
    private final NotificationService notices;
    private final Clock clock;
    public UserModerationService(ModerationRepository repository,ContentSupport support,BanExpiryService expiry,NotificationService notices,Clock clock){
        this.repository=repository;this.support=support;this.expiry=expiry;this.notices=notices;this.clock=clock;
    }

    @Transactional
    @cn.xinkelink.audit.AuditAction(module="users",action="restrictions",targetType="user",fields={"restrictions","version"})
    public Map<String,Object> restrictions(long id,ModerationModels.Restrictions body,AuthPrincipal principal){
        long admin=support.requireAdmin(principal);
        if(body==null||body.enabled()==null||!Set.of("publish","comment","message").contains(body.channel()==null?"":body.channel()))throw failure(ErrorCode.VALIDATION_FAILED);
        confirmed(body.confirmed(),body.expectedVersion());String reason=nonblank(body.reason(),200);
        Map<String,Object> user=locked(id,body.expectedVersion());
        if(!body.enabled()&&banned(user))throw failure(ErrorCode.STATE_CONFLICT);
        String column="restrict_"+body.channel();
        if(flag(user,column)==body.enabled())throw failure(ErrorCode.STATE_UNCHANGED);
        Map<String,Object> before=userState(user);
        // 列名仅由服务端白名单通道生成，值始终参数化。
        repository.jdbc.update("UPDATE xl_user SET "+column+"=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",body.enabled(),id);
        return finish(id,admin,(body.enabled()?"restrict_":"release_")+body.channel(),reason,before);
    }

    @Transactional
    @cn.xinkelink.audit.AuditAction(module="users",action="ban",targetType="user",fields={"banState","banUntil","version"})
    public Map<String,Object> ban(long id,ModerationModels.Ban body,AuthPrincipal principal){
        long admin=support.requireAdmin(principal);
        if(body==null||!Set.of("temporary","permanent").contains(body.mode()==null?"":body.mode())
                ||body.durationDays()!=null&&body.durationDays()!=7)throw failure(ErrorCode.VALIDATION_FAILED);
        confirmed(body.confirmed(),body.expectedVersion());String reason=nonblank(body.reason(),200);
        Map<String,Object> user=locked(id,body.expectedVersion());
        if("permanent".equals(user.get("ban_state")))throw failure("permanent".equals(body.mode())?ErrorCode.STATE_UNCHANGED:ErrorCode.STATE_CONFLICT);
        if("temporary".equals(user.get("ban_state"))&&"temporary".equals(body.mode()))throw failure(ErrorCode.STATE_UNCHANGED);
        Map<String,Object> before=userState(user);
        repository.jdbc.update("UPDATE xl_user SET ban_state=?,ban_until=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",
                body.mode(),body.mode().equals("temporary")?Timestamp.from(clock.instant().plus(Duration.ofDays(7))):null,id);
        repository.jdbc.update("UPDATE xl_auth_session SET revoked_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE subject_type='user' AND subject_id=? AND revoked_at IS NULL",id);
        return finish(id,admin,body.mode().equals("temporary")?"temporary_ban":"permanent_ban",reason,before);
    }

    @Transactional
    @cn.xinkelink.audit.AuditAction(module="users",action="release",targetType="user",fields={"restrictions","banState","version"})
    public Map<String,Object> release(long id,ModerationModels.Release body,AuthPrincipal principal){
        long admin=support.requireAdmin(principal);
        if(body==null)throw failure(ErrorCode.VALIDATION_FAILED);
        confirmed(body.confirmed(),body.expectedVersion());String reason=nonblank(body.reason(),200);
        Map<String,Object> user=locked(id,body.expectedVersion());
        if("permanent".equals(user.get("ban_state")))throw failure(ErrorCode.STATE_CONFLICT);
        if("none".equals(user.get("ban_state"))&&!flag(user,"restrict_publish")&&!flag(user,"restrict_comment")&&!flag(user,"restrict_message"))throw failure(ErrorCode.STATE_UNCHANGED);
        Map<String,Object> before=userState(user);
        repository.jdbc.update("UPDATE xl_user SET ban_state='none',ban_until=NULL,restrict_publish=0,restrict_comment=0,restrict_message=0,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",id);
        return finish(id,admin,"lift_restrictions",reason,before);
    }

    private Map<String,Object> locked(long id,Long version){
        Map<String,Object> row=repository.user(id,true);
        if(row==null||row.get("deleted_at")!=null)throw failure(ErrorCode.NOT_FOUND);
        // 先核对管理员读到的版本，再规范同一行的到期层，避免规范化被冲突回滚后永远无法操作。
        ContentSupport.version(number(row,"version"),version);
        expiry.normalize(id);return repository.user(id,false);
    }
    private Map<String,Object> finish(long id,long admin,String action,String reason,Map<String,Object> before){
        Map<String,Object> user=repository.user(id,false);
        String operation=repository.history("user_management",null,"user",id,id,admin,action,reason,before,userState(user));
        notices.moderation(id,"moderation:user:"+operation,"账号权限调整","你的账号权限已由平台调整，如有疑问请联系平台。",null,null);
        return projection(user);
    }
    Map<String,Object> projection(Map<String,Object> user){
        long id=number(user,"id");boolean ban=banned(user);
        Map<String,Object> manual=map("publish",flag(user,"restrict_publish"),"comment",flag(user,"restrict_comment"),"message",flag(user,"restrict_message"));
        String state="permanent".equals(user.get("ban_state"))?"permanently_banned":ban?"banned":manual.containsValue(true)?"restricted":"active";
        Map<String,Object> counts=repository.one("SELECT (SELECT COUNT(*) FROM xl_post WHERE author_id=? AND deleted_at IS NULL) AS posts,(SELECT COUNT(*) FROM xl_comment WHERE author_id=? AND deleted_at IS NULL) AS comments",id,id);
        return map("id",Long.toString(id),"name",user.get("nickname"),"avatar",null,"major",user.get("major"),"className",user.get("class_name"),"enrollmentYear",user.get("enrollment_year"),
                "selfReported",true,"joinedAt",time(user,"created_at"),"lastActiveAt",time(user,"last_active_at"),"status",state,
                "restrictions",map("publish",ban||flag(user,"restrict_publish"),"comment",ban||flag(user,"restrict_comment"),"message",ban||flag(user,"restrict_message")),"manualRestrictions",manual,
                "banUntil",ban?time(user,"ban_until"):null,"contentCounts",counts,"reportCount",repository.jdbc.queryForObject("SELECT COUNT(*) FROM xl_report WHERE target_user_id=?",Long.class,id),"version",number(user,"version"));
    }
    private boolean banned(Map<String,Object> row){return "permanent".equals(row.get("ban_state"))||"temporary".equals(row.get("ban_state"))&&time(row,"ban_until")!=null&&time(row,"ban_until").isAfter(clock.instant());}
}
