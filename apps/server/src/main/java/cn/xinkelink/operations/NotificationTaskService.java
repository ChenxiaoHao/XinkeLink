package cn.xinkelink.operations;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.notification.NotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static cn.xinkelink.content.ContentSupport.map;
import static cn.xinkelink.operations.OperationsModels.*;

@Service
@Profile("auth")
public class NotificationTaskService {
    private static final List<String> AUDIENCES=List.of("all","active","recent");
    private final OperationsRepository repository;
    private final ContentSupport support;
    private final NotificationService notifications;
    public NotificationTaskService(OperationsRepository repository,ContentSupport support,NotificationService notifications) { this.repository=repository;this.support=support;this.notifications=notifications; }

    @Transactional(readOnly=true)
    public Map<String,Object> list(String keyword,String audience,String status,Integer page,Integer pageSize,AuthPrincipal viewer) {
        support.requireAdmin(viewer);OperationsSupport.option(audience,AUDIENCES,true);OperationsSupport.option(status,List.of("draft","sent"),true);
        int current=OperationsSupport.page(page),size=OperationsSupport.pageSize(pageSize);String term=OperationsSupport.like(keyword);
        return map("items",repository.tasks(term,audience,status,current,size).stream().map(this::view).toList(),"total",repository.taskCount(term,audience,status),"page",current,"pageSize",size,"stats",repository.taskStats());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(long id,AuthPrincipal viewer) {
        support.requireAdmin(viewer);Map<String,Object> result=view(available(repository.task(id,false)));result.put("receiptSummary",repository.receiptSummary(id));return result;
    }
    @Transactional
    @cn.xinkelink.audit.AuditAction(module="notifications",action="create",targetType="notification",fields={"title","audience"})
    public Map<String,Object> create(CreateTask input,AuthPrincipal viewer) {
        long admin=support.requireAdmin(viewer);String title=OperationsSupport.text(input.title(),50),body=OperationsSupport.text(input.body(),500);
        OperationsSupport.option(input.audience(),AUDIENCES,false);OperationsSupport.request(input.clientRequestId());
        byte[] hash=ContentSupport.hash(map("title",title,"body",body,"audience",input.audience()));
        var row=repository.createTask(admin,title,body,input.audience(),input.clientRequestId(),hash);
        if(!Arrays.equals(hash,row.requestHash()))throw new BusinessException(ErrorCode.DUPLICATE_REQUEST_CONFLICT);
        return view(row);
    }
    @Transactional
    @cn.xinkelink.audit.AuditAction(module="notifications",action="edit",targetType="notification",fields={"title","audience","version"})
    public Map<String,Object> edit(long id,EditTask input,AuthPrincipal viewer) {
        long admin=support.requireAdmin(viewer);var row=available(repository.task(id,true));draft(row);ContentSupport.version(row.version(),input.expectedVersion());
        String title=OperationsSupport.text(input.title(),50),body=OperationsSupport.text(input.body(),500);OperationsSupport.option(input.audience(),AUDIENCES,false);
        if(title.equals(row.title())&&body.equals(row.body())&&input.audience().equals(row.audience()))throw new BusinessException(ErrorCode.STATE_UNCHANGED);
        repository.editTask(id,admin,title,body,input.audience());return view(repository.task(id,false));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> preview(String audience,AuthPrincipal viewer) {
        support.requireAdmin(viewer);OperationsSupport.option(audience,AUDIENCES,false);Instant computed=repository.now();var recipients=recipients(audience,computed,false);
        return map("recipientCount",recipients.size(),"audienceFingerprint",fingerprint(audience,recipients),"computedAt",computed);
    }
    @Transactional
    @cn.xinkelink.audit.AuditAction(module="notifications",action="send",targetType="notification",fields={"status","version"})
    public Map<String,Object> send(long id,SendTask input,AuthPrincipal viewer) {
        long admin=support.requireAdmin(viewer);OperationsSupport.confirm(input.expectedVersion(),input.confirmed());
        if(input.audienceFingerprint()==null||!input.audienceFingerprint().matches("[a-f0-9]{64}"))throw OperationsSupport.invalid();
        var row=available(repository.task(id,true));draft(row);ContentSupport.version(row.version(),input.expectedVersion());
        List<Long> recipients=recipients(row.audience(),repository.now(),true);
        if(recipients.isEmpty())throw new BusinessException(ErrorCode.NO_RECIPIENTS);
        if(recipients.size()>5000)throw new BusinessException(ErrorCode.AUDIENCE_TOO_LARGE);
        String fingerprint=fingerprint(row.audience(),recipients);
        if(!fingerprint.equals(input.audienceFingerprint()))throw new BusinessException(ErrorCode.AUDIENCE_CHANGED);
        int inserted=notifications.systemTask(id,recipients),actual=repository.taskReceiptCount(id);
        // sent只代表完整收件事务；不能把预览人数当成实际写入人数。
        if(inserted!=recipients.size()||actual!=inserted)throw new BusinessException(ErrorCode.AUDIENCE_CHANGED);
        repository.sent(id,admin,actual,fingerprint,UUID.randomUUID().toString());return view(repository.task(id,false));
    }
    private List<Long> recipients(String audience,Instant now,boolean lock) {
        var users=repository.audienceUsers(lock);
        Instant recent=now.minus(30,ChronoUnit.DAYS);
        return users.stream().filter(user->switch(audience) {
            case "all" -> true;
            case "active" -> active(user,now);
            case "recent" -> active(user,now)&&user.lastActiveAt()!=null&&!user.lastActiveAt().isBefore(recent);
            default -> throw OperationsSupport.invalid();
        }).map(AudienceUser::id).toList();
    }
    private boolean active(AudienceUser user,Instant now) {
        return !"permanent".equals(user.banState())&&!("temporary".equals(user.banState())&&user.banUntil()!=null&&user.banUntil().isAfter(now))
                &&!user.restrictPublish()&&!user.restrictComment()&&!user.restrictMessage();
    }
    private String fingerprint(String audience,List<Long> recipients) { return HexFormat.of().formatHex(ContentSupport.hash(map("audience",audience,"recipients",recipients))); }
    private TaskRow available(TaskRow row) { if(row==null)throw new BusinessException(ErrorCode.TARGET_UNAVAILABLE);return row; }
    private void draft(TaskRow row) { if(!"draft".equals(row.status()))throw new BusinessException(ErrorCode.STATE_CONFLICT); }
    private Map<String,Object> view(TaskRow row) { return map("id",Long.toString(row.id()),"title",row.title(),"body",row.body(),"audience",row.audience(),"status",row.status(),"createdAt",row.createdAt(),"updatedAt",row.updatedAt(),"sentAt",row.sentAt(),"recipientCount",row.recipientCount(),"deliveryMode","in_app","externalPush",false,"version",row.version()); }
}
