package cn.xinkelink.audit;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static cn.xinkelink.content.ContentSupport.map;

@Service @Profile("auth")
public class AuditQueryService {
    private final JdbcTemplate jdbc;private final ContentSupport support;private final JsonMapper json;
    public AuditQueryService(JdbcTemplate jdbc,ContentSupport support,JsonMapper json) {this.jdbc=jdbc;this.support=support;this.json=json;}
    public record Filter(String keyword,String actorKeyword,String adminId,String module,String action,String outcome,Instant from,Instant to) {}
    public Map<String,Object> list(Filter filter,Integer page,Integer pageSize,AuthPrincipal principal) {
        support.requireAdmin(principal);int current=page==null?1:page,size=pageSize==null?10:pageSize;
        if(current<1||!List.of(10,20,50).contains(size)||(long)(current-1)*size>Integer.MAX_VALUE)throw invalid();
        Query query=query(filter);long total=jdbc.queryForObject("SELECT COUNT(*) "+query.sql(),Long.class,query.args().toArray());
        List<Object> args=new ArrayList<>(query.args());args.add(size);args.add((long)(current-1)*size);
        var rows=jdbc.queryForList("SELECT l.* "+query.sql()+" ORDER BY l.created_at DESC,l.id DESC LIMIT ? OFFSET ?",args.toArray());
        Map<String,Object> stats=jdbc.queryForMap("""
                SELECT COUNT(*) total,COALESCE(SUM(outcome='success'),0) success,
                COALESCE(SUM(outcome='business_rejected'),0) businessRejected,COALESCE(SUM(outcome='execution_error'),0) executionErrors,
                COALESCE(SUM(created_at>=?),0) today FROM xl_audit_log
                """,Timestamp.from(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        return map("items",rows.stream().map(this::view).toList(),"total",total,"page",current,"pageSize",size,"stats",stats);
    }
    public Map<String,Object> detail(long id,AuthPrincipal principal) {
        support.requireAdmin(principal);var rows=jdbc.queryForList("SELECT * FROM xl_audit_log WHERE id=?",id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND);return view(rows.getFirst());
    }
    public byte[] export(Filter filter,AuthPrincipal principal) {
        support.requireAdmin(principal);Query query=query(filter);
        long total=jdbc.queryForObject("SELECT COUNT(*) "+query.sql(),Long.class,query.args().toArray());
        if(total>10000)throw new BusinessException(ErrorCode.EXPORT_TOO_LARGE);
        var rows=jdbc.queryForList("SELECT l.* "+query.sql()+" ORDER BY l.created_at DESC,l.id DESC LIMIT 10001",query.args().toArray());
        if(rows.size()>10000)throw new BusinessException(ErrorCode.EXPORT_TOO_LARGE);
        StringBuilder csv=new StringBuilder("\uFEFF时间,操作者,业务模块,操作类型,结果,结果码,目标数量,变更字段\r\n");
        for(var row:rows) {
            var data=view(row);var cells=List.of(data.get("createdAt"),row.get("actor_display"),data.get("module"),data.get("action"),data.get("outcome"),data.get("resultCode"),data.get("targetCount"),data.get("changedFields"));
            csv.append(String.join(",",cells.stream().map(AuditPolicy::csv).toList())).append("\r\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private Query query(Filter f) {
        if(f.from()!=null&&f.to()!=null&&!f.from().isBefore(f.to()))throw invalid();
        StringBuilder sql=new StringBuilder("FROM xl_audit_log l LEFT JOIN xl_admin a ON a.id=l.admin_id WHERE 1=1");List<Object> args=new ArrayList<>();
        String keyword=ContentSupport.keyword(f.keyword()),actor=ContentSupport.keyword(f.actorKeyword());
        if(!keyword.isEmpty()){sql.append(" AND (l.operation_id LIKE ? ESCAPE '!' OR l.actor_display LIKE ? ESCAPE '!' OR l.action LIKE ? ESCAPE '!')");String value=like(keyword);Collections.addAll(args,value,value,value);}
        if(!actor.isEmpty()){sql.append(" AND (l.actor_display LIKE ? ESCAPE '!' OR a.login_name LIKE ? ESCAPE '!')");Collections.addAll(args,like(actor),like(actor));}
        if(f.adminId()!=null){sql.append(" AND l.admin_id=?");args.add(ContentSupport.id(f.adminId()));}
        optional(sql,args,"module",f.module(),Set.of("posts","comments","reports","users","announcements","notifications","settings"));
        optional(sql,args,"outcome",f.outcome(),Set.of("success","business_rejected","execution_error"));
        if(f.action()!=null){if(!f.action().matches("[a-z_]{1,48}"))throw invalid();sql.append(" AND l.action=?");args.add(f.action());}
        if(f.from()!=null){sql.append(" AND l.created_at>=?");args.add(Timestamp.from(f.from()));}
        if(f.to()!=null){sql.append(" AND l.created_at<?");args.add(Timestamp.from(f.to()));}
        return new Query(sql.toString(),args);
    }
    private Map<String,Object> view(Map<String,Object> row) {
        Object raw=row.get("changed_fields");Object fields=raw instanceof byte[] bytes?json.readValue(bytes,List.class):json.readValue(String.valueOf(raw),List.class);
        return map("id",row.get("id").toString(),"operationId",row.get("operation_id"),"actorType",row.get("actor_type"),
                "actor",map("id",row.get("admin_id")==null?null:row.get("admin_id").toString(),"displayName",row.get("actor_display")),
                "module",row.get("module"),"action",row.get("action"),"targetType",row.get("target_type"),
                "targetRef",row.get("target_type")+":"+row.get("operation_id"),"targetCount",row.get("target_count"),
                "outcome",row.get("outcome"),"summaryCode",row.get("summary_code"),"resultCode",row.get("result_code"),"changedFields",fields,
                "createdAt",ContentSupport.instant(row.get("created_at")));
    }
    private static void optional(StringBuilder sql,List<Object> args,String column,String value,Set<String> allowed) {
        if(value==null)return;if(!allowed.contains(value))throw invalid();sql.append(" AND l.").append(column).append("=?");args.add(value);
    }
    private static String like(String value) {return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private static BusinessException invalid() {return new BusinessException(ErrorCode.VALIDATION_FAILED);}
    private record Query(String sql,List<Object> args) {}
}
