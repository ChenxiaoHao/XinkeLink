package cn.xinkelink.admin;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static cn.xinkelink.content.ContentSupport.map;

@Component
@Profile("auth")
class AdminData {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static final String POST_PUBLIC="p.deleted_at IS NULL AND p.status='visible' AND p.review_state='normal'";
    static final String COMMENT_PUBLIC="c.deleted_at IS NULL AND c.status='visible' AND (c.root_id IS NULL OR EXISTS (SELECT 1 FROM xl_comment root WHERE root.id=c.root_id AND root.deleted_at IS NULL AND root.status='visible')) AND c.target_type='post' AND EXISTS (SELECT 1 FROM xl_post p WHERE p.id=c.target_id AND "+POST_PUBLIC+")";
    static final String BANNED="(u.ban_state='permanent' OR (u.ban_state='temporary' AND u.ban_until>UTC_TIMESTAMP(3)))";
    static final String MANUAL="(u.restrict_publish=1 OR u.restrict_comment=1 OR u.restrict_message=1)";
    static final JsonMapper JSON=JsonMapper.builder().build();
    final JdbcTemplate jdbc;
    AdminData(JdbcTemplate jdbc){this.jdbc=jdbc;}
    Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
    Map<String,Object> required(String table,long id,boolean lock){
        var row=one("SELECT * FROM "+table+" WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(row==null)throw fail(ErrorCode.NOT_FOUND);return row;
    }
    long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    static BusinessException fail(ErrorCode code){return new BusinessException(code);}
    static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static boolean flag(Map<String,Object> row,String key){Object value=row.get(key);return Boolean.TRUE.equals(value)||value instanceof Number num&&num.intValue()!=0;}
    static Instant time(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value instanceof Timestamp t?t.toInstant():value instanceof Instant i?i:((LocalDateTime)value).toInstant(ZoneOffset.UTC);}
    static String title(Map<String,Object> row){Object value=row.get("title");if(value==null)value=row.get("content");if(value==null)value=row.get("nickname");String text=value==null?"内容已删除":value.toString();return text.substring(0,Math.min(text.length(),60));}
    static void option(String value,Set<String> values){if(value!=null&&!values.contains(value))throw fail(ErrorCode.VALIDATION_FAILED);}
    static void range(Instant from,Instant to){if(from!=null&&to!=null&&!from.isBefore(to))throw fail(ErrorCode.INVALID_RANGE);}
    static String like(String keyword){return "%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    static int page(Integer value){int page=value==null?1:value;if(page<1)throw fail(ErrorCode.VALIDATION_FAILED);return page;}
    static int pageSize(Integer value){int size=value==null?10:value;if(!Set.of(10,20,50).contains(size))throw fail(ErrorCode.VALIDATION_FAILED);return size;}
    static Map<String,Object> page(List<?> items,long total,int page,int size){return map("items",items,"total",total,"page",page,"pageSize",size);}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){return JSON.readValue(value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):value.toString(),LinkedHashMap.class);}
    static Map<String,Object> state(String type,Map<String,Object> row){
        var result=map("version",n(row,"version"),"deleted",row.get("deleted_at")!=null);
        result.put("status",row.get("status"));return result;
    }
    void history(String type,long id,long owner,long admin,String action,String reason,Map<String,Object> before,Map<String,Object> after){
        jdbc.update("INSERT INTO xl_moderation_action(operation_id,source,target_type,target_id,target_user_id,admin_id,action,reason,before_state,after_state) VALUES (?,'user_management',?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),type,id,owner,admin,action,reason,JSON.writeValueAsString(before),JSON.writeValueAsString(after));
    }
    Map<String,Object> history(Map<String,Object> row){return map("id",Long.toString(n(row,"id")),"source",row.get("source"),"action",row.get("action"),"reason",row.get("reason"),
            "operator",row.get("admin_id")==null?"system":map("id",Long.toString(n(row,"admin_id")),"displayName",row.get("display_name")),"createdAt",time(row,"created_at"),"beforeState",object(row.get("before_state")),"afterState",object(row.get("after_state")));}
}
