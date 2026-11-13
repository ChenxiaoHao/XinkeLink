package cn.xinkelink.admin;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.media.MediaService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.*;

import static cn.xinkelink.admin.AdminData.*;
import static cn.xinkelink.content.ContentSupport.map;

@Component
@Profile("auth")
class AdminProjection {
    private final AdminData data;
    private final MediaService media;
    private final Clock clock;
    AdminProjection(AdminData data,MediaService media,Clock clock){this.data=data;this.media=media;this.clock=clock;}
    Map<String,Object> person(long id,AuthPrincipal admin){
        var user=data.one("SELECT id,nickname,major,class_name,avatar_image_id,deleted_at FROM xl_user WHERE id=?",id);
        if(user==null||user.get("deleted_at")!=null)return map("id",null,"name","已注销用户","nickname","已注销用户","avatar",null,"selfReported",true);
        Object avatar=user.get("avatar_image_id")==null?null:media.access(n(user,"avatar_image_id"),admin);
        return map("id",Long.toString(id),"name",user.get("nickname"),"nickname",user.get("nickname"),"avatar",avatar,"major",user.get("major"),"className",user.get("class_name"),"selfReported",true);
    }
    Map<String,Object> post(Map<String,Object> row,AuthPrincipal admin){
        long id=n(row,"id");boolean deleted=row.get("deleted_at")!=null;
        var author=person(n(row,"author_id"),admin);
        long visible=data.count("SELECT COUNT(*) FROM xl_comment c WHERE c.target_type='post' AND c.target_id=? AND "+COMMENT_PUBLIC,id);
        return map("id",Long.toString(id),"title",deleted?"内容已删除":title(row),"category",row.get("category"),"content",deleted?null:row.get("content"),"author",author,
                "isAnonymous",flag(row,"is_anonymous"),"anonymousLabel",row.get("anonymous_label"),"images",deleted?List.of():media.images("post",List.of(id),admin).get(id),
                "status",row.get("status"),"reviewState",row.get("review_state"),"availability",availability("post",row),"createdAt",time(row,"created_at"),"updatedAt",time(row,"updated_at"),
                "likeCount",data.count("SELECT COUNT(*) FROM xl_like WHERE target_type='post' AND target_id=?",id),"commentCount",visible,"visibleCommentCount",visible,"viewCount",null,"version",n(row,"version"));
    }
    Map<String,Object> comment(Map<String,Object> row,AuthPrincipal admin){
        long id=n(row,"id"),target=n(row,"target_id");String type=row.get("target_type").toString();boolean deleted=row.get("deleted_at")!=null;
        var result=map("id",Long.toString(id),"targetType",type,"targetId",Long.toString(target),"rootId",row.get("root_id")==null?null:Long.toString(n(row,"root_id")),
                "replyToId",row.get("reply_to_id")==null?null:Long.toString(n(row,"reply_to_id")),"level",row.get("root_id")==null?"root":"reply","status",deleted?"deleted":row.get("status"),
                "availability",deleted?"deleted":row.get("status"),"content",deleted?null:row.get("content"),"createdAt",time(row,"created_at"),"version",n(row,"version"));
        if(deleted)return result;
        Map<String,Object> targetRow=data.one("SELECT * FROM "+"xl_post"+" WHERE id=?",target);
        result.put("linkedTarget",target(type,targetRow,target));result.put("author",person(n(row,"author_id"),admin));
        result.put("likeCount",data.count("SELECT COUNT(*) FROM xl_like WHERE target_type='comment' AND target_id=?",id));
        result.put("isPostAuthor",type.equals("post")&&targetRow!=null&&n(targetRow,"author_id")==n(row,"author_id"));
        Map<String,Object> replyTo=null;
        if(row.get("reply_to_id")!=null){var reply=data.one("SELECT author_id,deleted_at FROM xl_comment WHERE id=?",n(row,"reply_to_id"));
            replyTo=map("commentId",Long.toString(n(row,"reply_to_id")),"name",reply==null||reply.get("deleted_at")!=null?"已删除评论":person(n(reply,"author_id"),admin).get("name"));}
        result.put("replyTo",replyTo);
        result.put("effectiveVisible",data.count("SELECT COUNT(*) FROM xl_comment c WHERE c.id=? AND "+COMMENT_PUBLIC,id)>0);return result;
    }
    Map<String,Object> user(Map<String,Object> row,AuthPrincipal admin){
        long id=n(row,"id");boolean banned="permanent".equals(row.get("ban_state"))||"temporary".equals(row.get("ban_state"))&&time(row,"ban_until").isAfter(clock.instant());
        var manual=map("publish",flag(row,"restrict_publish"),"comment",flag(row,"restrict_comment"),"message",flag(row,"restrict_message"));
        String status="permanent".equals(row.get("ban_state"))?"permanently_banned":banned?"banned":manual.containsValue(true)?"restricted":"active";
        var counts=map("posts",data.count("SELECT COUNT(*) FROM xl_post WHERE author_id=? AND deleted_at IS NULL",id),"comments",data.count("SELECT COUNT(*) FROM xl_comment WHERE author_id=? AND deleted_at IS NULL",id));
        return map("id",Long.toString(id),"name",row.get("nickname"),"avatar",person(id,admin).get("avatar"),"bio",row.get("bio"),"major",row.get("major"),"className",row.get("class_name"),"enrollmentYear",row.get("enrollment_year"),
                "selfReported",true,"joinedAt",time(row,"created_at"),"lastActiveAt",time(row,"last_active_at"),"status",status,"manualRestrictions",manual,
                "restrictions",map("publish",banned||flag(row,"restrict_publish"),"comment",banned||flag(row,"restrict_comment"),"message",banned||flag(row,"restrict_message")),"banUntil",banned?time(row,"ban_until"):null,
                "contentCounts",counts,"counts",counts,"reportCount",data.count("SELECT COUNT(*) FROM xl_report WHERE target_user_id=?",id),"version",n(row,"version"));
    }
    Map<String,Object> report(Map<String,Object> row,long user,AuthPrincipal admin){
        String type=row.get("target_type").toString();long id=n(row,"target_id");String table=switch(type){case "post"->"xl_post";case "comment"->"xl_comment";case "user"->"xl_user";default->"xl_message";};
        var current=data.one("SELECT * FROM "+table+" WHERE id=?",id);List<String> roles=new ArrayList<>();
        if(n(row,"reporter_id")==user)roles.add("reporter");if(n(row,"target_user_id")==user)roles.add("target_user");
        return map("id",Long.toString(n(row,"id")),"type",type,"priority",row.get("priority"),"status",row.get("status"),"reasonCode",row.get("reason_code"),"description",row.get("description"),
                "reporter",person(n(row,"reporter_id"),admin),"targetUser",person(n(row,"target_user_id"),admin),"target",target(type,current,id),
                "createdAt",time(row,"created_at"),"processedAt",time(row,"resolved_at"),"version",n(row,"version"),"roles",roles);
    }
    Map<String,Object> target(String type,Map<String,Object> row,long id){return map("type",type,"id",Long.toString(id),"title",row==null||row.get("deleted_at")!=null?"内容已删除":type.equals("message")?"私信内容":title(row),"availability",availability(type,row));}
    String availability(String type,Map<String,Object> row){
        if(row==null)return "missing";if(row.get("deleted_at")!=null)return "deleted";
        if(type.equals("post"))return "hidden".equals(row.get("status"))?"hidden":"pending".equals(row.get("review_state"))?"pending":"available";
        return "hidden".equals(row.get(type.equals("message")?"moderation_state":"status"))?"hidden":"available";
    }
}
