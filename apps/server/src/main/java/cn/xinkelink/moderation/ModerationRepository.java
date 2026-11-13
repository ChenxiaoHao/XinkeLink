package cn.xinkelink.moderation;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static cn.xinkelink.content.ContentSupport.map;

@Component
@Profile("auth")
class ModerationRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    final JdbcTemplate jdbc;
    ModerationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
    Map<String,Object> user(long id,boolean lock){return one("SELECT id,nickname,bio,major,class_name,enrollment_year,ban_state,ban_until,restrict_publish,restrict_comment,restrict_message,created_at,last_active_at,version,deleted_at FROM xl_user WHERE id=?"+(lock?" FOR UPDATE":""),id);}
    Map<String,Object> report(long id,boolean lock){return one("SELECT * FROM xl_report WHERE id=?"+(lock?" FOR UPDATE":""),id);}
    Map<String,Object> target(String type,long id,boolean lock){
        String table=switch(type){case "post"->"xl_post";case "comment"->"xl_comment";case "user"->"xl_user";case "message"->"xl_message";default->throw failure(ErrorCode.UNSUPPORTED_TARGET);};
        if(type.equals("user"))return user(id,lock);
        return one("SELECT * FROM "+table+" WHERE id=?"+(lock?" FOR UPDATE":""),id);
    }
    String history(String source,Long reportId,String type,long targetId,Long targetUser,Long admin,
                   String action,String reason,Map<String,Object> before,Map<String,Object> after){
        String operation=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO xl_moderation_action(operation_id,source,report_id,target_type,target_id,target_user_id,admin_id,action,reason,before_state,after_state)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,operation,source,reportId,type,targetId,targetUser,admin,action,reason,json(before),json(after));
        return operation;
    }
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static boolean flag(Map<String,Object> row,String key){Object value=row.get(key);return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;}
    static Instant time(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value instanceof Timestamp t?t.toInstant():value instanceof Instant i?i:((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);}
    static String text(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value.toString();}
    static String json(Object value){return JSON.writeValueAsString(value);}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){return JSON.readValue(value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):value.toString(),LinkedHashMap.class);}
    static Map<String,Object> userState(Map<String,Object> row){return map("banState",row.get("ban_state"),"banUntil",time(row,"ban_until"),
            "manualRestrictions",map("publish",flag(row,"restrict_publish"),"comment",flag(row,"restrict_comment"),"message",flag(row,"restrict_message")),"version",number(row,"version"));}
    static Map<String,Object> targetState(String type,Map<String,Object> row){
        if(row==null)return map("availability","missing");
        if(type.equals("user"))return userState(row);
        Map<String,Object> result=map("version",number(row,"version"));
        result.put("status",row.get(type.equals("message")?"moderation_state":"status"));
        if(!type.equals("message"))result.put("deleted",row.get("deleted_at")!=null);
        return result;
    }
    static String nonblank(String value,int max){if(value==null||value.strip().isEmpty()||value.strip().length()>max)throw failure(ErrorCode.VALIDATION_FAILED);return value.strip();}
    static void confirmed(Boolean confirmed,Long version){if(!Boolean.TRUE.equals(confirmed)||version==null||version<0)throw failure(ErrorCode.VALIDATION_FAILED);}
    static BusinessException failure(ErrorCode code){return new BusinessException(code);}
}
