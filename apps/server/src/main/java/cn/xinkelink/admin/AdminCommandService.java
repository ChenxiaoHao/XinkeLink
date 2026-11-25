package cn.xinkelink.admin;

import cn.xinkelink.audit.AuditAction;
import cn.xinkelink.forum.metrics.PostMetricsCache;
import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.media.MediaService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static cn.xinkelink.admin.AdminData.*;
import static cn.xinkelink.content.ContentSupport.map;

@Service
@Profile("auth")
public class AdminCommandService {
    private final PostMetricsCache postMetrics;
    private final AdminData data;
    private final ContentSupport support;
    private final MediaService media;
    public AdminCommandService(AdminData data,ContentSupport support,MediaService media,PostMetricsCache postMetrics){this.postMetrics=postMetrics;this.data=data;this.support=support;this.media=media;}

    @Transactional
    @AuditAction(module="posts",action="set_status",targetType="post",fields={"status","version"})
    public Map<String,Object> postStatus(AdminModels.Batch body,AuthPrincipal admin){return status("post",body,admin);}
    @Transactional
    @AuditAction(module="comments",action="set_status",targetType="comment",fields={"status","version"})
    public Map<String,Object> commentStatus(AdminModels.Batch body,AuthPrincipal admin){return status("comment",body,admin);}
    @Transactional
    @AuditAction(module="posts",action="delete",targetType="post",fields={"deletedAt","version"})
    public Map<String,Object> deletePost(long id,long version,AuthPrincipal principal){
        long admin=support.requireAdmin(principal);var row=data.required("xl_post",id,true);ContentSupport.version(n(row,"version"),version);
        if(row.get("deleted_at")!=null)throw fail(ErrorCode.STATE_UNCHANGED);
        var before=state("post",row);
        int removed=data.jdbc.update("UPDATE xl_comment SET deleted_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE target_type='post' AND target_id=? AND deleted_at IS NULL",id);
        data.jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",id);media.detach("post",id);
        postMetrics.invalidateAfterCommit(List.of(id));
        data.history("post",id,n(row,"author_id"),admin,"delete_post","管理员删除帖子",before,state("post",data.required("xl_post",id,false)));
        return map("id",Long.toString(id),"deleted",true,"changed",true,"version",n(row,"version")+1,"removedCommentCount",removed);
    }
    @Transactional
    @AuditAction(module="comments",action="delete",targetType="comment",fields={"deletedAt","version"})
    public Map<String,Object> deleteComments(AdminModels.Batch body,AuthPrincipal principal){
        long admin=support.requireAdmin(principal);var items=items(body);confirmation(body);String reason=reason(body,"管理员删除评论");
        var rows=locked("comment",items);validateVersions(items,rows,false);
        Set<Long> roots=new TreeSet<>();for(var row:rows.values())if(row.get("root_id")==null)roots.add(n(row,"id"));
        Map<Long,Map<String,Object>> affected=new LinkedHashMap<>(rows);
        // 父内容行锁阻止并发新增回复；根和回复按ID锁定，重叠选择只删除一次。
        for(long root:roots)for(var reply:data.jdbc.queryForList("SELECT * FROM xl_comment WHERE root_id=? ORDER BY id FOR UPDATE",root))affected.put(n(reply,"id"),reply);
        Set<Long> changed=new HashSet<>();int removedReplies=0;
        for(var entry:affected.entrySet())if(entry.getValue().get("deleted_at")==null){
            long id=entry.getKey();var row=entry.getValue();changed.add(id);if(row.get("root_id")!=null)removedReplies++;
            data.jdbc.update("UPDATE xl_comment SET deleted_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",id);
            data.history("comment",id,n(row,"author_id"),admin,"delete_comment",reason,state("comment",row),state("comment",data.required("xl_comment",id,false)));
        }
        postMetrics.invalidateAfterCommit(affected.values().stream().filter(row -> changed.contains(n(row,"id")) && "post".equals(row.get("target_type"))).map(row -> n(row,"target_id")).distinct().toList());
        var result=result(items,rows,changed);result.put("removedReplyCount",removedReplies);result.put("removedCount",changed.size());return result;
    }
    private Map<String,Object> status(String type,AdminModels.Batch body,AuthPrincipal principal){
        long admin=support.requireAdmin(principal);var items=items(body);String target=body.targetStatus();
        if(target==null||!Set.of("visible","hidden").contains(target))throw fail(ErrorCode.VALIDATION_FAILED);
        String reason=reason(body,"管理员调整内容状态");var rows=locked(type,items);validateVersions(items,rows,true);
        Set<Long> changed=new HashSet<>();
        for(var entry:rows.entrySet()) {
            long id=entry.getKey();var row=entry.getValue();boolean change;
            change=!target.equals(row.get("status"));if(change)data.jdbc.update("UPDATE xl_"+type+" SET status=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",target,id);
            if(change){changed.add(id);data.history(type,id,n(row,"author_id"),admin,"set_"+type+"_status",reason,state(type,row),state(type,data.required("xl_"+type,id,false)));}
        }
        postMetrics.invalidateAfterCommit(rows.values().stream().filter(row -> changed.contains(n(row,"id")))
                .filter(row -> type.equals("post") || "post".equals(row.get("target_type")))
                .map(row -> type.equals("post") ? n(row,"id") : n(row,"target_id")).distinct().toList());
        return result(items,rows,changed);
    }
    private LinkedHashMap<Long,AdminModels.Item> items(AdminModels.Batch body){
        if(body==null||body.items()==null||body.items().isEmpty()||body.items().size()>100)throw fail(ErrorCode.VALIDATION_FAILED);
        LinkedHashMap<Long,AdminModels.Item> result=new LinkedHashMap<>();
        for(var item:body.items()){if(item==null||item.expectedVersion()==null||item.expectedVersion()<0)throw fail(ErrorCode.VALIDATION_FAILED);long id=ContentSupport.id(item.id());if(result.put(id,item)!=null)throw fail(ErrorCode.VALIDATION_FAILED);}return result;
    }
    private Map<Long,Map<String,Object>> locked(String type,Map<Long,AdminModels.Item> items){
        if(type.equals("comment")) {
            SortedMap<String,Long> parents=new TreeMap<>();
            for(long id:items.keySet()){var row=data.required("xl_comment",id,false);String parent=row.get("target_type").toString();long target=n(row,"target_id");parents.put(parent+":"+String.format("%020d",target),target);}
            for(var parent:parents.entrySet())data.required("xl_post",parent.getValue(),true);
        }
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();for(long id:new TreeSet<>(items.keySet()))result.put(id,data.required("xl_"+type,id,true));return result;
    }
    private void validateVersions(Map<Long,AdminModels.Item> items,Map<Long,Map<String,Object>> rows,boolean active){
        for(var entry:rows.entrySet()){var row=entry.getValue();ContentSupport.version(n(row,"version"),items.get(entry.getKey()).expectedVersion());if(active&&row.get("deleted_at")!=null)throw fail(ErrorCode.STATE_CONFLICT);}
    }
    private Map<String,Object> result(Map<Long,AdminModels.Item> items,Map<Long,Map<String,Object>> before,Set<Long> changed){
        long actual=items.keySet().stream().filter(changed::contains).count();if(actual==0)throw fail(ErrorCode.STATE_UNCHANGED);
        List<Map<String,Object>> results=new ArrayList<>();for(long id:items.keySet()){boolean didChange=changed.contains(id);results.add(map("id",Long.toString(id),"outcome",didChange?"changed":"unchanged","code",didChange?"OK":"STATE_UNCHANGED","newVersion",n(before.get(id),"version")+(didChange?1:0)));}
        return map("requestedCount",items.size(),"changedCount",actual,"skippedCount",items.size()-actual,"items",results);
    }
    private void confirmation(AdminModels.Batch body){if(!Boolean.TRUE.equals(body.confirmed()))throw fail(ErrorCode.VALIDATION_FAILED);}
    private String reason(AdminModels.Batch body,String fallback){if(body.reason()==null)return fallback;String reason=body.reason().strip();if(reason.isEmpty()||reason.length()>200)throw fail(ErrorCode.VALIDATION_FAILED);return reason;}
}
