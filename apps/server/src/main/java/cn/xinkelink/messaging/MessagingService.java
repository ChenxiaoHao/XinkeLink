package cn.xinkelink.messaging;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentCursor;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.content.security.ContentScene;
import cn.xinkelink.content.security.ContentSecurityService;
import cn.xinkelink.notification.NotificationService;
import cn.xinkelink.realtime.RealtimeEvents;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.xinkelink.content.ContentSupport.map;
import static cn.xinkelink.messaging.MessagingStore.*;

@Service
@Profile("auth")
public class MessagingService {
    private final MessagingStore store;
    private final ContentSupport support;
    private final ContentCursor cursors;
    private final NotificationService notifications;
    private final RealtimeEvents events;
    private final ContentSecurityService contentSecurity;
    public MessagingService(MessagingStore store,ContentSupport support,ContentCursor cursors,
            NotificationService notifications,RealtimeEvents events){
        this(store,support,cursors,notifications,events,(ContentSecurityService)null);
    }
    @Autowired
    public MessagingService(MessagingStore store,ContentSupport support,ContentCursor cursors,
            NotificationService notifications,RealtimeEvents events,ObjectProvider<ContentSecurityService> contentSecurity){
        this(store,support,cursors,notifications,events,contentSecurity.getIfAvailable());
    }
    private MessagingService(MessagingStore store,ContentSupport support,ContentCursor cursors,
            NotificationService notifications,RealtimeEvents events,ContentSecurityService contentSecurity){
        this.store=store;this.support=support;this.cursors=cursors;this.notifications=notifications;this.events=events;this.contentSecurity=contentSecurity;
    }

    @Transactional(readOnly=true)
    public CursorPage<Map<String,Object>> list(String cursor,Integer requestedSize,AuthPrincipal actor){
        long user=support.require(actor,null);int size=ContentSupport.size(requestedSize);
        var position=cursors.read(cursor,"conversations",user);
        if(position.at()!=null&&(position.value()==null||position.value()>1))throw new BusinessException(ErrorCode.CURSOR_INVALID);
        List<Conversation> rows=new ArrayList<>(store.conversations(user,position,size+1));
        boolean more=rows.size()>size;if(more)rows.removeLast();
        String next=null;
        if(more){var last=rows.getLast();next=cursors.next("conversations",user,new ContentCursor.Position(position.snapshot(),last.activity(),last.id(),last.pinned()?1L:0L));}
        return new CursorPage<>(conversations(rows,actor),next,more);
    }

    @Transactional
    public Map<String,Object> open(MessagingModels.OpenConversation body,AuthPrincipal actor){
        if(actor==null)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if(!"user".equals(actor.actorType()))throw new BusinessException(ErrorCode.FORBIDDEN);
        if(body==null)throw invalid();long user=actor.subjectId(),peer=ContentSupport.id(body.peerUserId());
        if(user==peer)throw new BusinessException(ErrorCode.STATE_CONFLICT);
        long low=Math.min(user,peer),high=Math.max(user,peer);
        // 双向同时打开也按同一顺序锁账号，避免用户对唯一约束与FK产生死锁。
        store.lockUsers(low,high);support.require(actor,null);
        if(!store.userExists(peer))throw new BusinessException(ErrorCode.NOT_FOUND);
        long id=store.open(low,high);
        Conversation row=member(id,user,false);conversationEvents(id,user,peer);
        return conversations(List.of(row),actor).getFirst();
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(long id,AuthPrincipal actor){
        long user=support.require(actor,null);return conversations(List.of(member(id,user,false)),actor).getFirst();
    }

    @Transactional(readOnly=true)
    public Map<String,Object> history(long id,String beforeValue,String afterValue,String aroundValue,Integer requestedSize,String q,AuthPrincipal actor){
        long user=support.require(actor,null);Conversation row=member(id,user,false);
        int size=requestedSize==null?20:requestedSize;if(size<1||size>100)throw invalid();
        int modes=(beforeValue==null?0:1)+(afterValue==null?0:1)+(aroundValue==null?0:1);
        if(modes>1||aroundValue!=null&&q!=null)throw invalid();
        BigInteger before=beforeValue==null?null:MessagingModels.sequence(beforeValue);
        BigInteger after=afterValue==null?null:MessagingModels.sequence(afterValue);
        String keyword=ContentSupport.keyword(q),pattern=keyword.isEmpty()?null:pattern(keyword);
        List<Message> rows;
        boolean more;
        if(aroundValue!=null){
            Message target=store.message(ContentSupport.id(aroundValue));
            if(target==null||target.conversation()!=id||target.sequence().compareTo(row.clear())<=0)throw new BusinessException(ErrorCode.NOT_FOUND);
            int half=size/2;
            rows=new ArrayList<>(store.history(id,row.clear(),target.sequence(),null,null,half));
            rows.add(target);rows.addAll(store.history(id,row.clear(),null,target.sequence(),null,Math.min(half,99-half)));
            more=false;
        }else{
            rows=new ArrayList<>(store.history(id,row.clear(),before,after,pattern,size+1));
            more=rows.size()>size;if(more)rows.removeLast();
        }
        rows.sort(Comparator.comparing(Message::sequence));
        String nextBefore=null,nextAfter=null;
        if(!rows.isEmpty()){
            Message first=rows.getFirst(),last=rows.getLast();
            if(!store.history(id,row.clear(),first.sequence(),null,pattern,1).isEmpty())nextBefore=first.sequence().toString();
            if(!store.history(id,row.clear(),null,last.sequence(),pattern,1).isEmpty())nextAfter=last.sequence().toString();
        }
        if(aroundValue!=null)more=nextBefore!=null||nextAfter!=null;
        return map("items",messages(rows,actor),"hasMore",more,"nextBeforeSequence",nextBefore,"nextAfterSequence",nextAfter);
    }

    @Transactional
    public Map<String,Object> send(long id,MessagingModels.SendMessage body,AuthPrincipal actor){
        long user=support.require(actor,"message");
        if(body==null||body.text()==null||body.text().strip().isEmpty()||body.text().strip().length()>1000
                ||body.clientMessageId()==null||!body.clientMessageId().matches("[\\x21-\\x7E]{1,64}"))throw invalid();
        String text=body.text().strip();
        if(contentSecurity!=null)contentSecurity.checkText(user,ContentScene.MESSAGE,text);
        byte[] hash=ContentSupport.hash(map("conversationId",Long.toString(id),"text",text));
        Conversation row=member(id,user,true);
        // 发送者账号行锁串行化同主体的幂等键；原消息优先于当前屏蔽变化。
        Message existing=store.request(user,body.clientMessageId());
        if(existing!=null){
            if(existing.conversation()!=id||!Objects.equals(existing.text(),text))throw new BusinessException(ErrorCode.DUPLICATE_REQUEST_CONFLICT);
            return messages(List.of(existing),actor).getFirst();
        }
        if(store.blocked(user,row.peer(user)))throw new BusinessException(ErrorCode.MESSAGE_BLOCKED);
        if(row.next().compareTo(MessagingModels.MAX_SEQUENCE)>=0)throw new BusinessException(ErrorCode.STATE_CONFLICT);
        Message message=store.insert(row,user,body.clientMessageId(),hash,text);
        for(long recipient:new long[]{row.low(),row.high()}){
            AuthPrincipal viewer=recipient==user?actor:new AuthPrincipal(recipient,0,"user");
            queue(recipient,"message.created",messages(List.of(message),viewer).getFirst());
            queueConversation(id,viewer);queueUnread(viewer);
        }
        return messages(List.of(message),actor).getFirst();
    }

    @Transactional
    public Map<String,Object> read(long id,MessagingModels.Read body,AuthPrincipal actor){
        long user=support.require(actor,null);Conversation row=member(id,user,true);
        BigInteger value=MessagingModels.sequence(body==null?null:body.readUpToSequence());
        if(value.compareTo(row.next().subtract(BigInteger.ONE))>0)throw new BusinessException(ErrorCode.INVALID_RANGE);
        boolean changed=store.read(id,user,value)>0;Conversation updated=member(id,user,false);
        // 只在已读水位实际前进时广播，避免聊天页收到自己的事件后循环重载。
        if(changed)readEvents(updated,actor);
        return map("readThroughSequence",updated.read().toString(),"unreadCount",unread(id,user));
    }

    @Transactional
    public Map<String,Object> readAll(AuthPrincipal actor){
        long user=support.require(actor,null);
        // 先捕获所有已提交上界，再更新成员；更新等待期间到来的消息不进入捕获结果。
        List<Watermark> captured=store.capture(user);List<Map<String,Object>> watermarks=new ArrayList<>();
        for(Watermark watermark:captured){
            boolean changed=store.read(watermark.conversation(),user,watermark.highest())>0;
            Conversation row=member(watermark.conversation(),user,false);
            watermarks.add(map("conversationId",Long.toString(row.id()),"readThroughSequence",row.read().toString()));
            if(changed)readEvents(row,actor);
        }
        return map("watermarks",watermarks,"unread",notifications.unread(actor));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> settings(long id,AuthPrincipal actor){
        long user=support.require(actor,null);return settings(member(id,user,false),user);
    }

    @Transactional
    public Map<String,Object> settings(long id,MessagingModels.Settings body,AuthPrincipal actor){
        long user=support.require(actor,null);Conversation row=member(id,user,true);
        if(body==null||body.pinned()==null&&body.muted()==null)throw invalid();
        ContentSupport.version(row.version(),body.expectedVersion());
        boolean pinned=body.pinned()==null?row.pinned():body.pinned(),muted=body.muted()==null?row.muted():body.muted();
        if(pinned!=row.pinned()||muted!=row.muted()){
            store.settings(id,user,pinned,muted);queueConversation(id,actor);
        }
        return settings(member(id,user,false),user);
    }

    @Transactional
    public Map<String,Object> clear(long id,MessagingModels.Clear body,AuthPrincipal actor){
        long user=support.require(actor,null);Conversation row=member(id,user,true);
        if(body==null||!Boolean.TRUE.equals(body.confirmed()))throw invalid();
        BigInteger value=MessagingModels.sequence(body.clearThroughSequence());
        if(value.compareTo(row.clear())<0||value.compareTo(row.next().subtract(BigInteger.ONE))>0)throw new BusinessException(ErrorCode.INVALID_RANGE);
        store.clear(id,user,value);Conversation updated=member(id,user,false);readEvents(updated,actor);
        return map("clearedThroughSequence",updated.clear().toString(),"unreadCount",unread(id,user));
    }

    private Conversation member(long id,long user,boolean lock){
        if(id<1)throw invalid();Conversation row=store.conversation(id,user,lock);
        if(row==null)throw new BusinessException(ErrorCode.NOT_FOUND);return row;
    }
    private long unread(long id,long user){return store.unreads(user,List.of(id)).getOrDefault(id,0L);}
    private Map<String,Object> settings(Conversation row,long user){return map("pinned",row.pinned(),"muted",row.muted(),"blocked",store.blocked(user,row.peer(user)),"version",row.version());}
    private List<Map<String,Object>> conversations(List<Conversation> rows,AuthPrincipal actor){
        if(rows.isEmpty())return List.of();long user=actor.subjectId();
        List<Long> ids=rows.stream().map(Conversation::id).toList();
        var peers=support.users(rows.stream().map(row->row.peer(user)).toList());
        var unreads=store.unreads(user,ids);
        var last=store.messages(rows.stream().map(Conversation::lastMessage).filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(Message::id,Function.identity()));
        return rows.stream().map(row->{Message message=row.lastMessage()==null?null:last.get(row.lastMessage());
            return map("id",Long.toString(row.id()),"peer",peers.get(row.peer(user)),"lastMessage",message==null||message.sequence().compareTo(row.clear())<=0?null:message(message,user),
                    "updatedAt",row.updated(),"unreadCount",unreads.getOrDefault(row.id(),0L),
                    "pinned",row.pinned(),"muted",row.muted(),"version",row.version());}).toList();
    }
    private List<Map<String,Object>> messages(List<Message> rows,AuthPrincipal actor){
        return rows.stream().map(row->message(row,actor.subjectId())).toList();
    }
    private Map<String,Object> message(Message row,long user){
        boolean hidden=!"normal".equals(row.visibility());
        return map("id",Long.toString(row.id()),"conversationId",Long.toString(row.conversation()),"sequence",row.sequence().toString(),
                "clientMessageId",hidden?null:row.client(),"senderId",hidden?null:Long.toString(row.sender()),"isMine",!hidden&&row.sender()==user,
                "kind","text","text",hidden?null:row.text(),
                "createdAt",row.created(),"persistenceStatus","sent","visibility",hidden?"hidden":"normal");
    }
    private void conversationEvents(long id,long first,long second){
        queueConversation(id,new AuthPrincipal(first,0,"user"));queueConversation(id,new AuthPrincipal(second,0,"user"));
    }
    private void queueConversation(long id,AuthPrincipal actor){
        Conversation row=store.conversation(id,actor.subjectId(),false);
        if(row!=null)queue(actor.subjectId(),"conversation.updated",conversations(List.of(row),actor).getFirst());
    }
    private void queueUnread(AuthPrincipal actor){queue(actor.subjectId(),"unread.updated",notifications.unreadForUser(actor.subjectId()));}
    private void readEvents(Conversation row,AuthPrincipal actor){
        Map<String,Object> payload=map("conversationId",Long.toString(row.id()),"userId",Long.toString(actor.subjectId()),"readThroughSequence",row.read().toString());
        queue(row.low(),"read.updated",payload);queue(row.high(),"read.updated",payload);
        queueConversation(row.id(),actor);queueUnread(actor);
    }
    // 事务内捕获每位接收者的安全投影，提交回调只推送，避免额外连接和旧事务读取。
    private void queue(long user,String type,Map<String,Object> payload){events.afterCommit(()->events.publish(user,type,payload));}
    private static String pattern(String keyword){return "%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private static BusinessException invalid(){return new BusinessException(ErrorCode.VALIDATION_FAILED);}
}
