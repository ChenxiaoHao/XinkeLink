package cn.xinkelink.operations;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static cn.xinkelink.operations.OperationsModels.*;

@Repository
@Profile("auth")
public class OperationsRepository {
    private final JdbcTemplate jdbc;
    public OperationsRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Instant time(ResultSet row,String field)throws SQLException { Timestamp value=row.getTimestamp(field);return value==null?null:value.toInstant(); }
    private static final RowMapper<AnnouncementRow> ANNOUNCEMENT=(r,n)->new AnnouncementRow(r.getLong("id"),r.getString("title"),r.getString("body"),r.getString("category"),r.getString("scope"),r.getString("status"),r.getLong("created_by"),r.getLong("updated_by"),r.getBytes("request_hash"),time(r,"first_published_at"),time(r,"last_published_at"),time(r,"withdrawn_at"),time(r,"created_at"),time(r,"updated_at"),r.getLong("version"),time(r,"deleted_at"));
    private static final RowMapper<TaskRow> TASK=(r,n)->new TaskRow(r.getLong("id"),r.getString("title"),r.getString("body"),r.getString("audience"),r.getString("status"),r.getLong("created_by"),r.getLong("updated_by"),r.getBytes("request_hash"),time(r,"sent_at"),r.getObject("recipient_count",Integer.class),time(r,"created_at"),time(r,"updated_at"),r.getLong("version"));
    Instant now() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",Timestamp.class).toInstant(); }
    AnnouncementRow announcement(long id,boolean lock) { return first(jdbc.query("SELECT * FROM xl_announcement WHERE id=?"+(lock?" FOR UPDATE":""),ANNOUNCEMENT,id)); }
    TaskRow task(long id,boolean lock) { return first(jdbc.query("SELECT * FROM xl_notification_task WHERE id=?"+(lock?" FOR UPDATE":""),TASK,id)); }
    private static <T> T first(List<T> rows) { return rows.isEmpty()?null:rows.getFirst(); }

    AnnouncementRow createAnnouncement(long admin,String title,String body,String category,String scope,String request,byte[] hash) {
        // 唯一请求键串行化并发重试；不覆盖初始摘要及编辑后的正文。
        int changed=jdbc.update("INSERT IGNORE INTO xl_announcement(title,body,category,scope,created_by,updated_by,client_request_id,request_hash) VALUES (?,?,?,?,?,?,?,?)",title,body,category,scope,admin,admin,request,hash);
        cn.xinkelink.audit.AuditChanges.mark(changed==1);
        return jdbc.queryForObject("SELECT * FROM xl_announcement WHERE created_by=? AND client_request_id=? FOR UPDATE",ANNOUNCEMENT,admin,request);
    }
    TaskRow createTask(long admin,String title,String body,String audience,String request,byte[] hash) {
        int changed=jdbc.update("INSERT IGNORE INTO xl_notification_task(title,body,audience,created_by,updated_by,client_request_id,request_hash) VALUES (?,?,?,?,?,?,?)",title,body,audience,admin,admin,request,hash);
        cn.xinkelink.audit.AuditChanges.mark(changed==1);
        return jdbc.queryForObject("SELECT * FROM xl_notification_task WHERE created_by=? AND client_request_id=? FOR UPDATE",TASK,admin,request);
    }
    void editAnnouncement(long id,long admin,String title,String body,String category,String scope) {
        jdbc.update("UPDATE xl_announcement SET title=?,body=?,category=?,scope=?,status='draft',updated_by=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",title,body,category,scope,admin,id);
    }
    void publish(long id,long admin) {
        jdbc.update("UPDATE xl_announcement SET status='published',first_published_at=COALESCE(first_published_at,UTC_TIMESTAMP(3)),last_published_at=UTC_TIMESTAMP(3),updated_by=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",admin,id);
    }
    void withdraw(long id,long admin) {
        jdbc.update("UPDATE xl_announcement SET status='withdrawn',withdrawn_at=UTC_TIMESTAMP(3),updated_by=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",admin,id);
    }
    void deleteAnnouncement(long id,long admin) {
        jdbc.update("UPDATE xl_announcement SET deleted_at=UTC_TIMESTAMP(3),updated_by=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",admin,id);
    }
    void editTask(long id,long admin,String title,String body,String audience) {
        jdbc.update("UPDATE xl_notification_task SET title=?,body=?,audience=?,updated_by=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",title,body,audience,admin,id);
    }
    void sent(long id,long admin,int count,String fingerprint,String operation) {
        jdbc.update("UPDATE xl_notification_task SET status='sent',recipient_count=?,audience_fingerprint=?,send_operation_id=?,sent_at=UTC_TIMESTAMP(3),updated_by=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",count,fingerprint,operation,admin,id);
    }
    int taskReceiptCount(long id) { return jdbc.queryForObject("SELECT COUNT(DISTINCT recipient_id) FROM xl_user_notification WHERE task_id=?",Integer.class,id); }
    Map<String,Object> receiptSummary(long id) { return jdbc.queryForMap("SELECT COUNT(*) AS recipientCount,COALESCE(SUM(read_at IS NOT NULL),0) AS readCount FROM xl_user_notification WHERE task_id=?",id); }
    Map<Long,String> adminNames(Collection<Long> ids) {
        if(ids.isEmpty())return Map.of();
        Map<Long,String> names=new HashMap<>();
        jdbc.query("SELECT id,display_name FROM xl_admin WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",r->{names.put(r.getLong("id"),r.getString("display_name"));},ids.toArray());
        return names;
    }
    List<AnnouncementRow> publicAnnouncements(String scope,Instant snapshot,Instant at,long id,int limit) {
        List<Object> args=new ArrayList<>();String where="status='published' AND deleted_at IS NULL AND last_published_at<=? AND ";args.add(Timestamp.from(snapshot));
        if("all".equals(scope))where+="scope='all'";
        else {where+="scope IN ('all',?)";args.add(scope);}
        if(at!=null){where+=" AND (last_published_at<? OR (last_published_at=? AND id<?))";args.add(Timestamp.from(at));args.add(Timestamp.from(at));args.add(id);}
        args.add(limit);
        return jdbc.query("SELECT * FROM xl_announcement WHERE "+where+" ORDER BY last_published_at DESC,id DESC LIMIT ?",ANNOUNCEMENT,args.toArray());
    }
    List<AnnouncementRow> announcements(String keyword,String category,String status,String scope,int page,int size) {
        Query filter=announcementFilter(keyword,category,status,scope);filter.args.add(size);filter.args.add(((long)page-1)*size);
        return jdbc.query("SELECT * FROM xl_announcement WHERE "+filter.where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",ANNOUNCEMENT,filter.args.toArray());
    }
    long announcementCount(String keyword,String category,String status,String scope) {
        Query filter=announcementFilter(keyword,category,status,scope);return jdbc.queryForObject("SELECT COUNT(*) FROM xl_announcement WHERE "+filter.where,Long.class,filter.args.toArray());
    }
    Map<String,Object> announcementStats() { return jdbc.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(status='published'),0) AS published,COALESCE(SUM(status='draft'),0) AS draft,COALESCE(SUM(status='withdrawn'),0) AS withdrawn FROM xl_announcement WHERE deleted_at IS NULL"); }
    List<TaskRow> tasks(String keyword,String audience,String status,int page,int size) {
        Query filter=taskFilter(keyword,audience,status);filter.args.add(size);filter.args.add(((long)page-1)*size);
        return jdbc.query("SELECT * FROM xl_notification_task WHERE "+filter.where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",TASK,filter.args.toArray());
    }
    long taskCount(String keyword,String audience,String status) {
        Query filter=taskFilter(keyword,audience,status);return jdbc.queryForObject("SELECT COUNT(*) FROM xl_notification_task WHERE "+filter.where,Long.class,filter.args.toArray());
    }
    Map<String,Object> taskStats() { return jdbc.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(status='sent'),0) AS sent,COALESCE(SUM(status='draft'),0) AS draft,COALESCE(SUM(CASE WHEN status='sent' THEN recipient_count ELSE 0 END),0) AS inAppRecipientCount FROM xl_notification_task"); }
    private record Query(String where,List<Object> args) {}
    private Query announcementFilter(String keyword,String category,String status,String scope) {
        StringBuilder where=new StringBuilder("deleted_at IS NULL");List<Object> args=new ArrayList<>();
        keyword(where,args,keyword);equal(where,args,"category",category);equal(where,args,"status",status);equal(where,args,"scope",scope);
        return new Query(where.toString(),args);
    }
    private Query taskFilter(String keyword,String audience,String status) {
        StringBuilder where=new StringBuilder("1=1");List<Object> args=new ArrayList<>();
        keyword(where,args,keyword);equal(where,args,"audience",audience);equal(where,args,"status",status);return new Query(where.toString(),args);
    }
    private void equal(StringBuilder where,List<Object> args,String column,String value) { if(value!=null){where.append(" AND ").append(column).append("=?");args.add(value);} }
    private void keyword(StringBuilder where,List<Object> args,String value) { if(value!=null){where.append(" AND (title LIKE ? ESCAPE '!' OR body LIKE ? ESCAPE '!')");args.add(value);args.add(value);} }

    List<AudienceUser> audienceUsers(boolean lock) {
        // 发送读取当前行并锁定账号，避免预览后的限制/删除状态被旧快照覆盖。
        return jdbc.query("SELECT id,ban_state,ban_until,restrict_publish,restrict_comment,restrict_message,last_active_at FROM xl_user WHERE deleted_at IS NULL ORDER BY id"+(lock?" FOR SHARE":""),
                (r,n)->new AudienceUser(r.getLong("id"),r.getString("ban_state"),time(r,"ban_until"),r.getBoolean("restrict_publish"),r.getBoolean("restrict_comment"),r.getBoolean("restrict_message"),time(r,"last_active_at")));
    }
}
