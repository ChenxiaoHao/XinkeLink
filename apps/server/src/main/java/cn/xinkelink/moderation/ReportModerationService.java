package cn.xinkelink.moderation;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.forum.metrics.PostMetricsCache;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.media.MediaService;
import cn.xinkelink.notification.NotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static cn.xinkelink.content.ContentSupport.map;
import static cn.xinkelink.moderation.ModerationRepository.*;

@Service
@Profile("auth")
public class ReportModerationService {
    private static final Set<String> TYPES=Set.of("post","comment","user","message");
    private static final Set<String> REASONS=Set.of("commercial_ad","fraud_or_offsite_payment","harassment","impersonation","other");
    private static final Map<String,Set<String>> ACTIONS=Map.of("post",Set.of("reject","hide_post"),"comment",Set.of("reject","hide_comment"),
            "user",Set.of("reject","warn_user","restrict_user"),"message",Set.of("reject","hide_message"));
    private final PostMetricsCache postMetrics;
    private final ModerationRepository repository;
    private final ContentSupport support;
    private final MediaService media;
    private final NotificationService notices;
    private final UserModerationService users;
    private final Clock clock;
    public ReportModerationService(ModerationRepository repository,ContentSupport support,MediaService media,NotificationService notices,UserModerationService users,Clock clock,PostMetricsCache postMetrics){this.postMetrics=postMetrics;
        this.repository=repository;this.support=support;this.media=media;this.notices=notices;this.users=users;this.clock=clock;
    }

    @Transactional
    public Map<String,Object> create(ModerationModels.CreateReport body,AuthPrincipal principal){
        ModerationModels.CreateReport input=normalize(body);
        if(principal==null)throw failure(ErrorCode.UNAUTHENTICATED);
        if(!"user".equals(principal.actorType()))throw failure(ErrorCode.FORBIDDEN);
        long targetId=ContentSupport.id(input.targetId());
        Map<String,Object> initial=repository.target(input.targetType(),targetId,false);
        // target_user_id外键也会取得用户行锁，统一按ID顺序锁举报双方，避免互报死锁。
        long targetOwner=initial==null?principal.subjectId():owner(input.targetType(),initial);
        repository.jdbc.queryForList("SELECT id FROM xl_user WHERE id IN (?,?) ORDER BY id FOR UPDATE",principal.subjectId(),targetOwner);
        long reporter=support.require(principal,null);
        byte[] hash=ContentSupport.hash(input);
        Map<String,Object> existing=repository.one("SELECT * FROM xl_report WHERE reporter_id=? AND client_request_id=?",reporter,input.clientRequestId());
        if(existing!=null){if(!Arrays.equals((byte[])existing.get("request_hash"),hash))throw failure(ErrorCode.DUPLICATE_REQUEST_CONFLICT);return receipt(existing);}
        if(repository.one("SELECT id FROM xl_report WHERE reporter_id=? AND target_type=? AND target_id=? AND status='pending'",reporter,input.targetType(),targetId)!=null)throw failure(ErrorCode.REPORT_PENDING);
        Map<String,Object> target=readableTarget(input.targetType(),targetId);
        validateEvidence(reporter,input.evidenceImageIds());
        long targetUser=owner(input.targetType(),target);
        Map<String,Object> snapshot=snapshot(input.targetType(),target);
        String priority=input.reasonCode().equals("fraud_or_offsite_payment")?"high":input.reasonCode().equals("commercial_ad")?"low":"medium";
        repository.jdbc.update("""
                INSERT INTO xl_report(reporter_id,target_type,target_id,target_user_id,reason_code,description,priority,target_snapshot,client_request_id,request_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,reporter,input.targetType(),targetId,targetUser,input.reasonCode(),input.description(),priority,json(snapshot),input.clientRequestId(),hash);
        Map<String,Object> report=repository.one("SELECT * FROM xl_report WHERE reporter_id=? AND client_request_id=?",reporter,input.clientRequestId());
        long reportId=number(report,"id");
        bindEvidence(reportId,input.evidenceImageIds());
        preserveImages(input.targetType(),targetId,reportId);
        return receipt(report);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> list(String keyword,String type,String priority,String status,Instant from,Instant to,Integer page,Integer pageSize,AuthPrincipal principal){
        support.requireAdmin(principal);
        String search=ContentSupport.keyword(keyword);
        option(type,TYPES);option(priority,Set.of("high","medium","low"));option(status,Set.of("pending","processed"));
        if(from!=null&&to!=null&&!from.isBefore(to))throw failure(ErrorCode.VALIDATION_FAILED);
        int current=page==null?1:page,size=pageSize==null?10:pageSize;
        if(current<1||!Set.of(10,20,50).contains(size))throw failure(ErrorCode.VALIDATION_FAILED);
        StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object> args=new ArrayList<>();
        if(!search.isEmpty()){
            String like="%"+search.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            where.append(" AND (r.description LIKE ? ESCAPE '!' OR EXISTS (SELECT 1 FROM xl_user u WHERE u.id=r.reporter_id AND u.nickname LIKE ? ESCAPE '!'))");args.add(like);args.add(like);
        }
        if(type!=null){where.append(" AND r.target_type=?");args.add(type);}
        if(priority!=null){where.append(" AND r.priority=?");args.add(priority);}
        if(status!=null){where.append(" AND r.status=?");args.add(status);}
        if(from!=null){where.append(" AND r.created_at>=?");args.add(Timestamp.from(from));}
        if(to!=null){where.append(" AND r.created_at<?");args.add(Timestamp.from(to));}
        long total=repository.jdbc.queryForObject("SELECT COUNT(*) FROM xl_report r"+where,Long.class,args.toArray());
        args.add(size);args.add(((long)current-1)*size);
        List<Map<String,Object>> rows=repository.jdbc.queryForList("SELECT r.* FROM xl_report r"+where+" ORDER BY CASE r.status WHEN 'pending' THEN 0 ELSE 1 END,CASE r.priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",args.toArray());
        Map<String,Object> stats=repository.one("SELECT COUNT(*) AS total,COALESCE(SUM(status='pending'),0) AS pending,COALESCE(SUM(status='processed'),0) AS processed,COALESCE(SUM(status='pending' AND priority='high'),0) AS highPriority FROM xl_report");
        return map("items",rows.stream().map(this::summary).toList(),"total",total,"page",current,"pageSize",size,"stats",stats);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(long id,AuthPrincipal principal){support.requireAdmin(principal);return detail(requiredReport(id,false),principal);}

    @Transactional
    @cn.xinkelink.audit.AuditAction(module="reports",action="resolve",targetType="report",fields={"status","version"})
    public Map<String,Object> resolve(long id,ModerationModels.Resolution body,AuthPrincipal principal){
        long admin=support.requireAdmin(principal);
        if(body==null)throw failure(ErrorCode.VALIDATION_FAILED);
        confirmed(body.confirmed(),body.expectedVersion());String reason=nonblank(body.reason(),200);
        Map<String,Object> report=requiredReport(id,true);
        if(!"pending".equals(report.get("status")))throw failure(ErrorCode.ALREADY_PROCESSED);
        ContentSupport.version(number(report,"version"),body.expectedVersion());
        String type=text(report,"target_type"),action=body.action();
        if(action==null||!ACTIONS.get(type).contains(action))throw failure(ErrorCode.VALIDATION_FAILED);
        long targetId=number(report,"target_id"),targetUser=number(report,"target_user_id");
        long reporter=number(report,"reporter_id");
        // 通知收件人外键参与同一事务，处置与提交共用双方用户的固定锁顺序。
        repository.jdbc.queryForList("SELECT id FROM xl_user WHERE id IN (?,?) ORDER BY id FOR UPDATE",reporter,targetUser);
        Map<String,Object> target=repository.target(type,targetId,true);
        if(!action.equals("reject")&&(target==null||!type.equals("message")&&target.get("deleted_at")!=null))throw failure(ErrorCode.STATE_CONFLICT);
        Map<String,Object> before=targetState(type,target);int changed=0;boolean noticeCreated=false;
        switch(action){
            case "hide_post","hide_comment" -> {
                if(!"hidden".equals(target.get("status"))){
                    String table=action.equals("hide_post")?"xl_post":"xl_comment";
                    changed=repository.jdbc.update("UPDATE "+table+" SET status='hidden',updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",targetId);
                }
            }

            case "hide_message" -> {
                if(!"hidden".equals(target.get("moderation_state")))changed=repository.jdbc.update("UPDATE xl_message SET moderation_state='hidden',updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",targetId);
            }
            case "restrict_user" -> {
                // 举报只增加人工限制；绝不覆盖封禁层或临时截止时间。
                if(!flag(target,"restrict_publish")||!flag(target,"restrict_comment")||!flag(target,"restrict_message"))
                    changed=repository.jdbc.update("UPDATE xl_user SET restrict_publish=1,restrict_comment=1,restrict_message=1,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",targetId);
            }
            case "warn_user" -> {changed=1;noticeCreated=true;}
            case "reject" -> {}
            default -> throw failure(ErrorCode.VALIDATION_FAILED);
        }
        if(changed > 0) {
            if(action.equals("hide_post")) postMetrics.invalidateAfterCommit(List.of(targetId));
            if(action.equals("hide_comment") && "post".equals(target.get("target_type")))
                postMetrics.invalidateAfterCommit(List.of(number(target,"target_id")));
        }
        Map<String,Object> afterTarget=repository.target(type,targetId,false);
        repository.jdbc.update("UPDATE xl_report SET status='processed',resolution=?,resolution_reason=?,resolved_by=?,resolved_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",action,reason,admin,id);
        repository.history("report",id,type,targetId,targetUser,admin,action,reason,before,targetState(type,afterTarget));
        // 站内提示只给安全结果摘要，不包含举报人、证据或匿名真实作者。
        notices.moderation(reporter,"moderation:report:"+id+":reporter","举报处理结果","你提交的举报已完成审核，请遵守社区规范并理性交流。",null,null);
        if(!action.equals("reject")&&targetUser!=reporter){
            notices.moderation(targetUser,"moderation:report:"+id+":target","社区规范提醒",action.equals("warn_user")?"请遵守社区规范，文明交流。":"你的相关内容或账号权限已由平台处理，如有疑问请联系平台。",null,null);
            noticeCreated=true;
        }
        if(action.equals("warn_user")&&targetUser==reporter)noticeCreated=true;
        Map<String,Object> effects=map("changedCount",changed,"noticeCreated",noticeCreated||reporter>0);
        if(Set.of("hide_post","hide_comment","hide_message").contains(action))effects.put("contentVisibility","hidden");
        if(action.equals("restrict_user"))effects.put("userStatus",users.projection(afterTarget).get("status"));
        return map("report",detail(requiredReport(id,false),principal),"effects",effects);
    }

    private Map<String,Object> readableTarget(String type,long id){
        Map<String,Object> row;
        if(type.equals("comment")){
            row=repository.target(type,id,false);
            if(row==null)throw failure(ErrorCode.TARGET_UNAVAILABLE);
            Map<String,Object> parent=repository.target(text(row,"target_type"),number(row,"target_id"),true);
            if(!"available".equals(availability(text(row,"target_type"),parent)))throw failure(ErrorCode.TARGET_UNAVAILABLE);
            if(row.get("root_id")!=null){Map<String,Object> root=repository.target("comment",number(row,"root_id"),true);if(!"available".equals(availability("comment",root)))throw failure(ErrorCode.TARGET_UNAVAILABLE);}
            row=repository.target(type,id,true);
        // 用户快照无需锁住对方账号，避免两人相互举报时反向取得用户锁。
        }else row=repository.target(type,id,!type.equals("user"));
        if(!"available".equals(availability(type,row)))throw failure(ErrorCode.TARGET_UNAVAILABLE);
        return row;
    }
    private void validateEvidence(long reporter,List<String> imageIds){
        List<Long> ids=imageIds.stream().map(ContentSupport::id).toList();
        for(long id:ids.stream().sorted().toList()){
            Map<String,Object> asset=repository.one("SELECT * FROM xl_image_asset WHERE id=? FOR UPDATE",id);
            if(asset==null||number(asset,"owner_user_id")!=reporter||!"report".equals(asset.get("purpose"))||!"temporary".equals(asset.get("state"))||asset.get("deleted_at")!=null
                    ||time(asset,"expires_at")==null||!time(asset,"expires_at").isAfter(clock.instant())||repository.one("SELECT image_id FROM xl_media_ref WHERE image_id=? LIMIT 1",id)!=null)throw failure(ErrorCode.IMAGE_FORBIDDEN);
        }
    }
    private void bindEvidence(long reportId,List<String> imageIds){
        List<Long> ids=imageIds.stream().map(ContentSupport::id).toList();
        for(int i=0;i<ids.size();i++){
            repository.jdbc.update("UPDATE xl_image_asset SET state='ready',expires_at=NULL,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",ids.get(i));
            repository.jdbc.update("INSERT INTO xl_media_ref(owner_type,owner_id,position,image_id) VALUES ('report_evidence',?,?,?)",reportId,i,ids.get(i));
        }
    }
    private void preserveImages(String type,long targetId,long reportId){
        if(!type.equals("post"))return;
        List<Map<String,Object>> refs=repository.jdbc.queryForList("SELECT r.position,r.image_id FROM xl_media_ref r JOIN xl_image_asset a ON a.id=r.image_id WHERE r.owner_type=? AND r.owner_id=? AND a.state='ready' AND a.deleted_at IS NULL ORDER BY a.id FOR UPDATE",type,targetId);
        for(Map<String,Object> ref:refs)repository.jdbc.update("INSERT INTO xl_media_ref(owner_type,owner_id,position,image_id) VALUES ('report_snapshot',?,?,?)",reportId,number(ref,"position"),number(ref,"image_id"));
    }
    private Map<String,Object> snapshot(String type,Map<String,Object> row){
        Map<String,Object> snapshot=map("type",type,"id",Long.toString(number(row,"id")),"title",title(type,row),"targetUserId",Long.toString(owner(type,row)));
        if(type.equals("post")||type.equals("comment"))snapshot.put("content",row.get("content"));
        if(type.equals("user")){snapshot.put("nickname",row.get("nickname"));snapshot.put("bio",row.get("bio"));}
        return snapshot;
    }
    private Map<String,Object> summary(Map<String,Object> row){
        String type=text(row,"target_type");long target=number(row,"target_id"),reporter=number(row,"reporter_id"),targetUser=number(row,"target_user_id");
        Map<String,Object> current=repository.target(type,target,false);String availability=availability(type,current);
        Map<Long,Map<String,Object>> people=support.users(List.of(reporter,targetUser));
        if(type.equals("user")&&current!=null&&people.get(targetUser)!=null){
            boolean banned="permanent".equals(current.get("ban_state"))||"temporary".equals(current.get("ban_state"))
                    &&time(current,"ban_until")!=null&&time(current,"ban_until").isAfter(clock.instant());
            String state="permanent".equals(current.get("ban_state"))?"permanently_banned":banned?"banned":
                    flag(current,"restrict_publish")||flag(current,"restrict_comment")||flag(current,"restrict_message")?"restricted":"active";
            people.get(targetUser).put("status",state);
        }
        return map("id",Long.toString(number(row,"id")),"type",type,"priority",row.get("priority"),"status",row.get("status"),"reasonCode",row.get("reason_code"),"description",row.get("description"),
                "reporter",people.get(reporter),"target",map("type",type,"id",Long.toString(target),"title",current==null||"deleted".equals(availability)?"内容已删除":title(type,current),"availability",availability),
                "targetUser",people.get(targetUser),"createdAt",time(row,"created_at"),"processedAt",time(row,"resolved_at"),"version",number(row,"version"));
    }
    private Map<String,Object> detail(Map<String,Object> row,AuthPrincipal admin){
        Map<String,Object> result=summary(row);long id=number(row,"id");
        Map<String,Object> snapshot=object(row.get("target_snapshot"));snapshot.put("images",media.reportImages(id,"report_snapshot",admin));
        result.put("targetSnapshot",snapshot);result.put("evidence",media.reportImages(id,"report_evidence",admin));
        result.put("resolution",row.get("resolution"));result.put("resolutionReason",row.get("resolution_reason"));
        List<Map<String,Object>> history=repository.jdbc.queryForList("SELECT m.*,a.display_name FROM xl_moderation_action m LEFT JOIN xl_admin a ON a.id=m.admin_id WHERE m.report_id=? ORDER BY m.created_at,m.id",id);
        result.put("history",history.stream().map(entry->map("id",Long.toString(number(entry,"id")),"source",entry.get("source"),"action",entry.get("action"),"reason",entry.get("reason"),
                "operator",entry.get("admin_id")==null?"system":map("id",Long.toString(number(entry,"admin_id")),"displayName",entry.get("display_name")),"createdAt",time(entry,"created_at"),"beforeState",object(entry.get("before_state")),"afterState",object(entry.get("after_state")))).toList());
        return result;
    }
    private Map<String,Object> requiredReport(long id,boolean lock){Map<String,Object> row=repository.report(id,lock);if(row==null)throw failure(ErrorCode.NOT_FOUND);return row;}
    private static Map<String,Object> receipt(Map<String,Object> row){return map("id",Long.toString(number(row,"id")),"status",row.get("status"),"createdAt",time(row,"created_at"));}
    private static String availability(String type,Map<String,Object> row){
        if(row==null)return "unavailable";
        if(!type.equals("message")&&row.get("deleted_at")!=null)return "deleted";
        if(type.equals("post"))return !"visible".equals(row.get("status"))?"hidden":!"normal".equals(row.get("review_state"))?"pending":"available";
        if(type.equals("comment")||type.equals("message"))return "hidden".equals(row.get(type.equals("message")?"moderation_state":"status"))?"hidden":"available";
        return "available";
    }
    private static String title(String type,Map<String,Object> row){
        String value=switch(type){case "post","comment"->text(row,"content");case "user"->text(row,"nickname");default->"指定消息";};
        if(value==null)return "";int end=Math.min(value.length(),60);if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end);
    }
    private static long owner(String type,Map<String,Object> row){return number(row,switch(type){case "post","comment"->"author_id";case "message"->"sender_id";default->"id";});}
    private static void option(String value,Set<String> options){if(value!=null&&!options.contains(value))throw failure(ErrorCode.VALIDATION_FAILED);}
    private static ModerationModels.CreateReport normalize(ModerationModels.CreateReport body){
        if(body==null)throw failure(ErrorCode.VALIDATION_FAILED);
        if(!Set.of("post","comment","user").contains(body.targetType()==null?"":body.targetType()))throw failure(ErrorCode.UNSUPPORTED_TARGET);
        long id=ContentSupport.id(body.targetId());
        if(!REASONS.contains(body.reasonCode()==null?"":body.reasonCode())||body.clientRequestId()==null||!body.clientRequestId().matches("[\\x21-\\x7E]{1,64}"))throw failure(ErrorCode.VALIDATION_FAILED);
        List<String> evidence=body.evidenceImageIds()==null?List.of():body.evidenceImageIds();
        if(evidence.size()>3)throw failure(ErrorCode.VALIDATION_FAILED);
        evidence=evidence.stream().map(value->Long.toString(ContentSupport.id(value))).toList();
        if(new HashSet<>(evidence).size()!=evidence.size())throw failure(ErrorCode.VALIDATION_FAILED);
        return new ModerationModels.CreateReport(body.targetType(),Long.toString(id),body.reasonCode(),nonblank(body.description(),200),evidence,body.clientRequestId());
    }
}
