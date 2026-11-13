package cn.xinkelink.notification;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentCursor;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.realtime.RealtimeEvents;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static cn.xinkelink.content.ContentSupport.map;

@Service
@Profile("auth")
public class NotificationService {
    private final NotificationMapper mapper;
    private final ContentSupport support;
    private final ContentCursor cursors;
    private final RealtimeEvents events;
    public NotificationService(NotificationMapper mapper,ContentSupport support,ContentCursor cursors,RealtimeEvents events){
        this.mapper=mapper;this.support=support;this.cursors=cursors;this.events=events;
    }
    public Map<String,Object> unread(AuthPrincipal viewer){return unreadFor(support.require(viewer,null));}
    /** 仅供已核实成员/接收者的内部事件投影，不映射任何HTTP入口。 */
    public Map<String,Object> unreadForUser(long userId){return unreadFor(userId);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void prepareReaction(String type,long target,AuthPrincipal actor){
        Set<Long> users=actors(actor);
        if("post".equals(type)){var post=mapper.post(target);if(post!=null)users.add(post.authorId());}
        else if("comment".equals(type)){var comment=mapper.comment(target);if(comment!=null)users.add(comment.authorId());}
        mapper.lockUsers(users);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void prepareComment(long postId,Long replyId,AuthPrincipal actor){
        Set<Long> users=actors(actor);var post=mapper.post(postId);if(post!=null)users.add(post.authorId());
        if(replyId!=null){var reply=mapper.comment(replyId);if(reply!=null)users.add(reply.authorId());}
        // 收件人外键会取共享锁，必须先按ID同时锁作者和接收者，再锁内容，避免双向互动死锁。
        mapper.lockUsers(users);
    }
    private Set<Long> actors(AuthPrincipal actor){
        if(actor==null)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if(!"user".equals(actor.actorType()))throw new BusinessException(ErrorCode.FORBIDDEN);
        Set<Long> users=new TreeSet<>();users.add(actor.subjectId());return users;
    }
    private Map<String,Object> unreadFor(long user){
        Map<String,Object> result=mapper.counts(user),raw=new LinkedHashMap<>();long total=0;
        for(String key:List.of("messages","comments","reactions","announcements")){
            long value=((Number)result.get(key)).longValue();raw.put(key,value);total+=value;
        }
        raw.put("total",total);Map<String,Object> preference=mapper.preferences(user);
        long badge=(enabled(preference,"messages")?(long)raw.get("messages"):0)
                +(enabled(preference,"interactions")?(long)raw.get("comments")+(long)raw.get("reactions"):0)
                +(enabled(preference,"announcements")?(long)raw.get("announcements"):0);
        return map("raw",raw,"badgeTotal",badge,"snapshotAt",Instant.now());
    }
    private static boolean enabled(Map<String,Object> preferences,String key){
        if(preferences==null||!preferences.containsKey(key))return true;Object value=preferences.get(key);
        return value instanceof Boolean flag?flag:((Number)value).intValue()!=0;
    }
    public CursorPage<Map<String,Object>> list(String type,boolean unreadOnly,String cursor,Integer size,AuthPrincipal viewer){
        long user=support.require(viewer,null);int limit=ContentSupport.size(size);
        if(type!=null&&!List.of("comment","reaction","announcement").contains(type))throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String scope="notices:"+type+":"+unreadOnly;var position=cursors.read(cursor,scope,user);
        var rows=mapper.list(user,type,unreadOnly,position.snapshot(),position.at(),position.id(),limit+1);
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(rows.size(),limit));String next=null;
        if(more){var last=page.getLast();next=cursors.next(scope,user,new ContentCursor.Position(position.snapshot(),last.createdAt(),last.id(),null));}
        return new CursorPage<>(page.stream().map(this::project).toList(),next,more);
    }
    @Transactional
    public Map<String,Object> read(long id,AuthPrincipal viewer){
        // 与取消互动在同一通知行上串行，避免标读过程中active失效导致结果查询为空。
        long user=support.require(viewer,null);var notice=mapper.findForUpdate(id,user);
        if(notice==null)throw new BusinessException(ErrorCode.NOT_FOUND);
        boolean changed=mapper.read(id,user)==1;
        if(changed)pushUnread(user);
        return map("readAt",mapper.find(id).readAt(),"changed",changed,"unread",unreadFor(user));
    }
    @Transactional
    public Map<String,Object> readAll(String throughNotificationId,AuthPrincipal viewer){
        long user=support.require(viewer,null);Long through=throughNotificationId==null?null:ContentSupport.id(throughNotificationId);
        // 先物化已提交的确切ID集合；不能用max(id)范围误标并发晚提交的通知。
        List<Long> ids=mapper.unreadIds(user,through);int changed=0;
        for(int start=0;start<ids.size();start+=500)changed+=mapper.readIds(user,ids.subList(start,Math.min(start+500,ids.size())));
        if(changed>0)pushUnread(user);
        return map("readCount",changed,"unread",unreadFor(user));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void reaction(String targetType,long target,long actor,String kind,boolean active){
        String key=kind+":"+target+":"+actor;
        if(!active){var receivers=mapper.receivers(key);mapper.deactivate(key);receivers.forEach(this::pushUnread);return;}
        Long recipient=null;
        if(targetType.equals("post")){var post=mapper.post(target);if(post!=null&&post.available())recipient=post.authorId();}
        else if(targetType.equals("comment")){var comment=mapper.comment(target);if(comment!=null&&comment.available())recipient=comment.authorId();}
        if(recipient!=null&&recipient!=actor)insert(recipient,key,"reaction",kind,actor,null,null,targetType,target,"互动提醒","");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void comment(long commentId){
        var comment=mapper.comment(commentId);if(comment==null||!comment.available())return;
        var post=mapper.post(comment.postId());Set<Long> recipients=new LinkedHashSet<>();recipients.add(post.authorId());
        if(comment.replyToId()!=null){var reply=mapper.comment(comment.replyToId());if(reply!=null)recipients.add(reply.authorId());}
        recipients.remove(comment.authorId());
        for(long receiver:recipients)insert(receiver,"comment:"+commentId,"comment",comment.replyToId()==null?"comment":"reply",
                comment.authorId(),null,null,"comment",commentId,"评论与回复","");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void moderation(long recipientId,String eventKey,String title,String summary,String targetType,Long targetId){
        insert(recipientId,eventKey,"announcement","moderation_warning",null,null,null,targetType,targetId,title,summary);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public int announcement(long announcementId){
        List<Long> users=mapper.recipients();if(users.size()>5000)throw new BusinessException(ErrorCode.AUDIENCE_TOO_LARGE);
        int count=0;for(long user:users)count+=insert(user,"announcement:"+announcementId+":first_publish","announcement","announcement",
                null,null,announcementId,"announcement",announcementId,"平台公告","");return count;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public int systemTask(long taskId,List<Long> recipients){
        if(recipients.size()>5000)throw new BusinessException(ErrorCode.AUDIENCE_TOO_LARGE);
        int count=0;for(long user:new LinkedHashSet<>(recipients))count+=insert(user,"task:"+taskId,"announcement","system",null,taskId,null,null,null,"系统通知","");return count;
    }
    private int insert(long recipient,String key,String type,String kind,Long actor,Long task,Long announcement,
                       String targetType,Long targetId,String title,String summary){
        if(mapper.recipientExists(recipient)==0||mapper.event(recipient,key)!=null)return 0;
        try{mapper.insert(recipient,key,type,kind,actor,task,announcement,targetType,targetId,title,summary);}
        catch(DuplicateKeyException alreadyExists){return 0;}
        long id=Objects.requireNonNull(mapper.event(recipient,key));
        events.afterCommit(()->{var notice=mapper.find(id);if(notice!=null){events.publish(recipient,"notification.created",project(notice));events.publish(recipient,"unread.updated",unreadFor(recipient));}});
        return 1;
    }
    private void pushUnread(long user){events.afterCommit(()->events.publish(user,"unread.updated",unreadFor(user)));}

    private Map<String,Object> project(NotificationMapper.NoticeRow notice){
        String kind=notice.targetType(),summary=notice.summary(),title=notice.title(),content=null,quote=null;
        boolean available=true;NotificationMapper.PostTarget post=null;
        if("post".equals(kind)){post=mapper.post(notice.targetId());available=post!=null&&post.available();if(available)quote=post.content();}
        else if("comment".equals(kind)){var comment=mapper.comment(notice.targetId());available=comment!=null&&comment.available();if(comment!=null){post=mapper.post(comment.postId());if(available)quote=comment.content();}}
        if(notice.announcementId()!=null){var announcement=mapper.announcement(notice.announcementId());
            available=announcement!=null&&"published".equals(announcement.get("status"))&&announcement.get("deletedAt")==null;
            if(available){title=(String)announcement.get("title");content=(String)announcement.get("body");}
        }
        if(notice.taskId()!=null){var task=mapper.task(notice.taskId());available=task!=null&&"sent".equals(task.get("status"));
            if(available){title=(String)task.get("title");content=(String)task.get("body");}}
        Map<String,Object> actor;
        if(notice.actorId()==null)actor=map("kind","platform","user",null,"displayName","信科 Link 平台通知");
        else if(available){boolean anonymous=post!=null&&post.anonymous()&&post.authorId()==notice.actorId();
            Map<String,Object> user=anonymous?map("id",null,"nickname",post.anonymousLabel(),"avatar",null,"isAnonymous",true)
                    :ordinaryActor(notice.actorId());actor=map("kind","user","user",user,"displayName",user.get("nickname"));
        }else actor=map("kind","user","user",map("id",null,"nickname","用户","avatar",null,"isAnonymous",false),"displayName","用户");
        if(!available){summary="内容已不可用";title="内容已不可用";content=null;quote=null;}
        if(content==null&&notice.actorId()==null&&notice.announcementId()==null&&notice.taskId()==null)content=summary;
        return map("id",Long.toString(notice.id()),"type",notice.type(),"eventKind",notice.eventKind(),"actor",actor,
                "title",title,"summary",summary,"content",content,"quote",quote,"image",null,
                "target",kind==null?null:map("kind",kind,"id",Long.toString(notice.targetId()),"availability",available?"available":"unavailable"),
                "readAt",notice.readAt(),"createdAt",notice.createdAt());
    }
    private Map<String,Object> ordinaryActor(long id){var user=support.users(List.of(id)).get(id);
        return user==null?map("id",null,"nickname","已注销用户","avatar",null,"isAnonymous",false)
                :map("id",user.get("id"),"nickname",user.get("nickname"),"avatar",user.get("avatar"),"isAnonymous",false);
    }
}
