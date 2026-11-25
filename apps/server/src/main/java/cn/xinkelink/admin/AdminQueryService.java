package cn.xinkelink.admin;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentCursor;
import cn.xinkelink.content.ContentSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;

import static cn.xinkelink.admin.AdminData.*;
import static cn.xinkelink.content.ContentSupport.map;

@Service
@Profile("auth")
@Transactional(readOnly=true)
public class AdminQueryService {
    private final AdminData data;
    private final AdminProjection views;
    private final ContentSupport support;
    private final ContentCursor cursors;
    private final Clock clock;
    public AdminQueryService(AdminData data,AdminProjection views,ContentSupport support,ContentCursor cursors,Clock clock){this.data=data;this.views=views;this.support=support;this.cursors=cursors;this.clock=clock;}

    public Map<String,Object> posts(Map<String,String> params,AuthPrincipal admin){
        support.requireAdmin(admin);Query q=new Query(params,"p.deleted_at IS NULL");
        q.search("p.content","u.nickname");q.option("category","p.category",Set.of("campus","lost_found","study","help","secondhand","complain","buddy"));q.option("status","p.status",Set.of("visible","hidden"));q.time("p.created_at");
        var result=list("xl_post p JOIN xl_user u ON u.id=p.author_id","p.*",q,row->views.post(row,admin));
        Instant start=today();result.put("stats",data.one("SELECT COUNT(*) AS total,COALESCE(SUM(status='visible' AND review_state='normal'),0) AS visible,COALESCE(SUM(status='hidden'),0) AS hidden,COALESCE(SUM(created_at>=?),0) AS today,COALESCE(SUM(review_state='pending'),0) AS pending FROM xl_post WHERE deleted_at IS NULL",Timestamp.from(start)));return result;
    }
    public Map<String,Object> post(long id,AuthPrincipal admin){
        support.requireAdmin(admin);var row=data.required("xl_post",id,false);
        long total=data.count("SELECT COUNT(*) FROM xl_comment WHERE target_type='post' AND target_id=? AND deleted_at IS NULL",id);
        long visible=data.count("SELECT COUNT(*) FROM xl_comment c WHERE c.target_type='post' AND c.target_id=? AND "+COMMENT_PUBLIC,id);
        return map("post",views.post(row,admin),"author",views.person(n(row,"author_id"),admin),"commentSummary",map("total",total,"visible",visible,"hidden",data.count("SELECT COUNT(*) FROM xl_comment WHERE target_type='post' AND target_id=? AND deleted_at IS NULL AND status='hidden'",id)));
    }
    public Map<String,Object> postComments(long id,Map<String,String> params,AuthPrincipal admin){
        support.requireAdmin(admin);data.required("xl_post",id,false);Query q=new Query(params,"c.target_type='post' AND c.root_id IS NULL");q.and("c.target_id=?",id);
        return list("xl_comment c","c.*",q,row->{long root=n(row,"id");var view=views.comment(row,admin);
            view.put("replyCount",data.count("SELECT COUNT(*) FROM xl_comment WHERE root_id=?",root));
            view.put("replies",data.jdbc.queryForList("SELECT * FROM xl_comment WHERE root_id=? ORDER BY created_at,id LIMIT 20",root).stream().map(reply->views.comment(reply,admin)).toList());return view;});
    }
    public Map<String,Object> comments(Map<String,String> params,AuthPrincipal admin){
        support.requireAdmin(admin);Query q=new Query(params,"c.deleted_at IS NULL");q.search("c.content","u.nickname");
        q.option("status","c.status",Set.of("visible","hidden"));String level=params.get("level");option(level,Set.of("root","reply"));if(level!=null)q.and("c.root_id IS "+(level.equals("root")?"NULL":"NOT NULL"));
        String business=params.get("business");option(business,Set.of("forum"));if(business!=null)q.and("c.target_type=?","post");q.time("c.created_at");
        var result=list("xl_comment c JOIN xl_user u ON u.id=c.author_id","c.*",q,row->views.comment(row,admin));
        result.put("stats",map("total",data.count("SELECT COUNT(*) FROM xl_comment WHERE deleted_at IS NULL"),"visible",data.count("SELECT COUNT(*) FROM xl_comment c WHERE "+COMMENT_PUBLIC),
                "hidden",data.count("SELECT COUNT(*) FROM xl_comment WHERE deleted_at IS NULL AND status='hidden'"),"today",data.count("SELECT COUNT(*) FROM xl_comment WHERE deleted_at IS NULL AND created_at>=?",Timestamp.from(today())),
                "interactions",data.count("SELECT COUNT(*) FROM xl_like l JOIN xl_comment c ON c.id=l.target_id WHERE l.target_type='comment' AND "+COMMENT_PUBLIC)));return result;
    }
    public Map<String,Object> context(long id,Map<String,String> params,AuthPrincipal admin){
        long actor=support.requireAdmin(admin);var selected=data.required("xl_comment",id,false);long rootId=selected.get("root_id")==null?id:n(selected,"root_id");var root=data.required("xl_comment",rootId,false);
        String scope="admin:comment-context:"+rootId;int size=ContentSupport.size(integer(params.get("size")));var position=cursors.read(params.get("replyCursor"),scope,actor);
        List<Object> args=new ArrayList<>(List.of(rootId,Timestamp.from(position.snapshot())));String where="root_id=? AND created_at<=?";
        if(position.at()!=null){where+=" AND (created_at>? OR (created_at=? AND id>?))";args.add(Timestamp.from(position.at()));args.add(Timestamp.from(position.at()));args.add(position.id());}args.add(size+1);
        var rows=data.jdbc.queryForList("SELECT * FROM xl_comment WHERE "+where+" ORDER BY created_at,id LIMIT ?",args.toArray());boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);String next=null;
        if(more){var last=rows.getLast();next=cursors.next(scope,actor,new ContentCursor.Position(position.snapshot(),time(last,"created_at"),n(last,"id"),null));}
        String type=selected.get("target_type").toString();long target=n(selected,"target_id");var targetRow=data.one("SELECT * FROM "+"xl_post"+" WHERE id=?",target);
        return map("target",views.target(type,targetRow,target),"root",views.comment(root,admin),"selected",views.comment(selected,admin),
                "replies",map("items",rows.stream().map(row->views.comment(row,admin)).toList(),"nextCursor",next,"hasMore",more),
                "visibility",map("target",views.availability(type,targetRow),"root",root.get("deleted_at")!=null?"deleted":root.get("status"),"selected",selected.get("deleted_at")!=null?"deleted":selected.get("status"),"effectiveVisible",data.count("SELECT COUNT(*) FROM xl_comment c WHERE c.id=? AND "+COMMENT_PUBLIC,id)>0));
    }
    public Map<String,Object> users(Map<String,String> params,AuthPrincipal admin){
        support.requireAdmin(admin);Query q=new Query(params,"u.deleted_at IS NULL");q.search("CAST(u.id AS CHAR)","u.nickname","u.major","u.class_name");q.time("u.created_at");
        String status=params.get("status");option(status,Set.of("active","restricted","banned","permanently_banned"));
        if(status!=null)q.and(switch(status){case "active"->"NOT "+BANNED+" AND NOT "+MANUAL;case "restricted"->"NOT "+BANNED+" AND "+MANUAL;case "banned"->"u.ban_state='temporary' AND u.ban_until>UTC_TIMESTAMP(3)";default->"u.ban_state='permanent'";});
        String restriction=params.get("restriction");option(restriction,Set.of("publish","comment","message"));if(restriction!=null)q.and("("+BANNED+" OR u.restrict_"+restriction+"=1)");
        var result=list("xl_user u","u.*",q,row->views.user(row,admin));
        result.put("stats",map("total",data.count("SELECT COUNT(*) FROM xl_user WHERE deleted_at IS NULL"),"todayNew",data.count("SELECT COUNT(*) FROM xl_user WHERE deleted_at IS NULL AND created_at>=?",Timestamp.from(today())),
                "restricted",data.count("SELECT COUNT(*) FROM xl_user u WHERE u.deleted_at IS NULL AND u.ban_state<>'permanent' AND ("+BANNED+" OR "+MANUAL+")"),"permanentlyBanned",data.count("SELECT COUNT(*) FROM xl_user WHERE deleted_at IS NULL AND ban_state='permanent'")));return result;
    }
    public Map<String,Object> user(long id,AuthPrincipal admin){support.requireAdmin(admin);return views.user(userRow(id),admin);}
    public Map<String,Object> userContent(long id,Map<String,String> params,AuthPrincipal admin){
        support.requireAdmin(admin);userRow(id);String type=params.get("type");if(type==null)throw fail(ErrorCode.VALIDATION_FAILED);option(type,Set.of("post","comment"));
        String alias=type.equals("comment")?"c":"p";Query q=new Query(params,alias+".deleted_at IS NULL");q.and(alias+"."+"author_id"+"=?",id);
        return list("xl_"+type+" "+alias,alias+".*",q,row->switch(type){case "post"->views.post(row,admin);default->views.comment(row,admin);});
    }
    public Map<String,Object> userReports(long id,Map<String,String> params,AuthPrincipal admin){
        support.requireAdmin(admin);userRow(id);Query q=new Query(params,"(r.reporter_id=? OR r.target_user_id=?)");q.args.add(id);q.args.add(id);
        return list("xl_report r","r.*",q,row->views.report(row,id,admin));
    }
    public Map<String,Object> userHistory(long id,Map<String,String> params,AuthPrincipal admin){
        support.requireAdmin(admin);userRow(id);Query q=new Query(params,"m.target_user_id=?");q.args.add(id);
        return list("xl_moderation_action m LEFT JOIN xl_admin a ON a.id=m.admin_id","m.*,a.display_name",q,data::history);
    }
    public Map<String,Object> dashboard(String range,AuthPrincipal admin){
        support.requireAdmin(admin);range=range==null?"7d":range;option(range,Set.of("7d","30d","semester"));Instant asOf=clock.instant();LocalDate today=asOf.atZone(ZONE).toLocalDate();
        LocalDate first=range.equals("semester")?LocalDate.of(today.getYear(),today.getMonthValue()<7?1:7,1):today.minusDays(range.equals("30d")?29:6);
        Instant start=first.atStartOfDay(ZONE).toInstant();List<Map<String,Object>> trends=new ArrayList<>();
        var counts=data.jdbc.queryForList("SELECT DATE(DATE_ADD(created_at,INTERVAL 8 HOUR)) AS day,COUNT(*) AS posts FROM xl_post WHERE deleted_at IS NULL AND created_at>=? AND created_at<? GROUP BY day",Timestamp.from(start),Timestamp.from(asOf));
        Map<String,Long> days=new HashMap<>();for(var row:counts)days.put(row.get("day").toString(),n(row,"posts"));
        for(LocalDate day=first;!day.isAfter(today);day=day.plusDays(1))trends.add(map("date",day.toString(),"postCount",days.getOrDefault(day.toString(),0L),"dau",null));
        var metrics=map("totalUsers",data.count("SELECT COUNT(*) FROM xl_user WHERE deleted_at IS NULL"),"todayPosts",data.count("SELECT COUNT(*) FROM xl_post WHERE deleted_at IS NULL AND created_at>=?",Timestamp.from(today())),
                "pendingReports",data.count("SELECT COUNT(*) FROM xl_report WHERE status='pending'"));
        var todoRows=data.jdbc.queryForList("""
                SELECT 'report' AS kind,id,'举报待审核' AS title,priority,created_at FROM xl_report WHERE status='pending'
                UNION ALL SELECT 'post',id,LEFT(content,60),'medium',created_at FROM xl_post WHERE deleted_at IS NULL AND review_state='pending'
                ORDER BY FIELD(priority,'high','medium','low'),created_at DESC,id DESC LIMIT 10
                """);
        var todos=todoRows.stream().map(row->map("kind",row.get("kind"),"id",Long.toString(n(row,"id")),"title",row.get("title"),"priority",row.get("priority"),"createdAt",time(row,"created_at"))).toList();
        var activity=data.jdbc.queryForList("""
                SELECT 'post' AS kind,id,created_at FROM xl_post WHERE deleted_at IS NULL
                UNION ALL SELECT 'report',id,created_at FROM xl_report
                UNION ALL SELECT 'announcement',id,last_published_at FROM xl_announcement WHERE status='published'
                ORDER BY created_at DESC,id DESC LIMIT 10
                """).stream().map(row->{String kind=row.get("kind").toString();return map("id",kind+":"+n(row,"id"),"kind",kind,"summary",switch(kind){case "post"->"新增论坛帖子";case "report"->"收到社区举报";default->"平台公告已发布";},"createdAt",time(row,"created_at"),"target",map("kind",kind,"id",Long.toString(n(row,"id"))));}).toList();
        return map("asOf",asOf,"range",range,"rangeStart",start,"rangeEnd",asOf,"metrics",metrics,"trends",trends,"todos",todos,"recentActivity",activity,
                "unavailableMetrics",List.of(map("key","dau","reason","尚未持续采集完整用户活动日数据"),map("key","averageSessionDuration","reason","尚未采集停留时长"),map("key","periodChange","reason","尚未建立可比较的历史指标快照")));
    }
    private Instant today(){return clock.instant().atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();}
    private Map<String,Object> userRow(long id){var row=data.required("xl_user",id,false);if(row.get("deleted_at")!=null)throw fail(ErrorCode.NOT_FOUND);return row;}
    private Map<String,Object> list(String table,String columns,Query q,Function<Map<String,Object>,Map<String,Object>> projection){
        String alias=table.substring(table.indexOf(' ')+1).split(" ")[0];long total=data.count("SELECT COUNT(*) FROM "+table+" WHERE "+q.where,q.args.toArray());List<Object> args=new ArrayList<>(q.args);args.add(q.size);args.add(((long)q.page-1)*q.size);
        var rows=data.jdbc.queryForList("SELECT "+columns+" FROM "+table+" WHERE "+q.where+" ORDER BY "+alias+".created_at DESC,"+alias+".id DESC LIMIT ? OFFSET ?",args.toArray());return page(rows.stream().map(projection).toList(),total,q.page,q.size);
    }
    private static Integer integer(String value){try{return value==null?null:Integer.valueOf(value);}catch(NumberFormatException exception){throw fail(ErrorCode.VALIDATION_FAILED);}}
    private static class Query {
        final Map<String,String> params;final StringBuilder where;final List<Object> args=new ArrayList<>();final int page,size;
        Query(Map<String,String> params,String where){this.params=params;this.where=new StringBuilder(where);this.page=page(integer(params.get("page")));this.size=pageSize(integer(params.get("pageSize")));}
        void and(String condition,Object...values){where.append(" AND (").append(condition).append(')');args.addAll(Arrays.asList(values));}
        void option(String key,String column,Set<String> values){String value=params.get(key);AdminData.option(value,values);if(value!=null)and(column+"=?",value);}
        void search(String...columns){String value=ContentSupport.keyword(params.get("keyword"));if(!value.isEmpty()){List<String> conditions=new ArrayList<>();for(String column:columns){conditions.add(column+" LIKE ? ESCAPE '!'");args.add(like(value));}where.append(" AND (").append(String.join(" OR ",conditions)).append(')');}}
        void time(String column){try{Instant from=params.get("from")==null?null:Instant.parse(params.get("from")),to=params.get("to")==null?null:Instant.parse(params.get("to"));range(from,to);if(from!=null)and(column+">=?",Timestamp.from(from));if(to!=null)and(column+"<?",Timestamp.from(to));}catch(java.time.format.DateTimeParseException exception){throw fail(ErrorCode.VALIDATION_FAILED);}}
    }
}
