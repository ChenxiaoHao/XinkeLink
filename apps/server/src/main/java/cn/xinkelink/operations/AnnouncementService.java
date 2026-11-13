package cn.xinkelink.operations;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentCursor;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.notification.NotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.xinkelink.content.ContentSupport.map;
import static cn.xinkelink.operations.OperationsModels.*;

@Service
@Profile("auth")
public class AnnouncementService {
    private static final List<String> CATEGORIES=List.of("community","activity","safety");
    private static final List<String> SCOPES=List.of("all","forum");
    private static final List<String> STATUSES=List.of("draft","published","withdrawn");
    private final OperationsRepository repository;
    private final ContentSupport support;
    private final ContentCursor cursors;
    private final NotificationService notifications;
    public AnnouncementService(OperationsRepository repository,ContentSupport support,ContentCursor cursors,NotificationService notifications) {
        this.repository=repository;this.support=support;this.cursors=cursors;this.notifications=notifications;
    }

    public CursorPage<Map<String,Object>> list(String scope,String cursor,Integer size,AuthPrincipal viewer) {
        OperationsSupport.option(scope,SCOPES,false);int limit=ContentSupport.size(size);String key="announcements:"+scope;
        Long user=support.viewer(viewer);var position=cursors.read(cursor,key,user);
        var rows=repository.publicAnnouncements(scope,position.snapshot(),position.at(),position.id(),limit+1);
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size()));String next=null;
        if(more){var last=page.getLast();next=cursors.next(key,user,new ContentCursor.Position(position.snapshot(),last.lastPublishedAt(),last.id(),null));}
        return new CursorPage<>(page.stream().map(this::publicView).toList(),next,more);
    }
    public Map<String,Object> detail(long id) {
        var row=available(repository.announcement(id,false));
        if(!"published".equals(row.status()))throw unavailable();
        return publicView(row);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> adminList(String keyword,String category,String status,String scope,Integer page,Integer pageSize,AuthPrincipal viewer) {
        support.requireAdmin(viewer);OperationsSupport.option(category,CATEGORIES,true);OperationsSupport.option(status,STATUSES,true);OperationsSupport.option(scope,SCOPES,true);
        int current=OperationsSupport.page(page),size=OperationsSupport.pageSize(pageSize);String term=OperationsSupport.like(keyword);
        var rows=repository.announcements(term,category,status,scope,current,size);Set<Long> admins=new HashSet<>();
        rows.forEach(row->{admins.add(row.createdBy());admins.add(row.updatedBy());});var names=repository.adminNames(admins);
        return map("items",rows.stream().map(row->adminView(row,names)).toList(),"total",repository.announcementCount(term,category,status,scope),"page",current,"pageSize",size,"stats",repository.announcementStats());
    }
    public Map<String,Object> adminDetail(long id,AuthPrincipal viewer) { support.requireAdmin(viewer);return adminView(available(repository.announcement(id,false))); }

    @Transactional
    @cn.xinkelink.audit.AuditAction(module="announcements",action="create",targetType="announcement",fields={"title","category","scope"})
    public Map<String,Object> create(CreateAnnouncement input,AuthPrincipal viewer) {
        long admin=support.requireAdmin(viewer);String title=OperationsSupport.text(input.title(),60),body=OperationsSupport.text(input.body(),1200);
        OperationsSupport.option(input.category(),CATEGORIES,false);OperationsSupport.option(input.scope(),SCOPES,false);OperationsSupport.request(input.clientRequestId());
        byte[] hash=ContentSupport.hash(map("title",title,"body",body,"category",input.category(),"scope",input.scope()));
        var row=repository.createAnnouncement(admin,title,body,input.category(),input.scope(),input.clientRequestId(),hash);
        if(!Arrays.equals(hash,row.requestHash()))throw new BusinessException(ErrorCode.DUPLICATE_REQUEST_CONFLICT);
        return adminView(available(row));
    }
    @Transactional
    @cn.xinkelink.audit.AuditAction(module="announcements",action="edit",targetType="announcement",fields={"title","category","scope","status","version"})
    public Map<String,Object> edit(long id,EditAnnouncement input,AuthPrincipal viewer) {
        long admin=support.requireAdmin(viewer);var row=available(repository.announcement(id,true));ContentSupport.version(row.version(),input.expectedVersion());
        String title=OperationsSupport.text(input.title(),60),body=OperationsSupport.text(input.body(),1200);
        OperationsSupport.option(input.category(),CATEGORIES,false);OperationsSupport.option(input.scope(),SCOPES,false);
        if("draft".equals(row.status())&&title.equals(row.title())&&body.equals(row.body())&&input.category().equals(row.category())&&input.scope().equals(row.scope()))
            throw new BusinessException(ErrorCode.STATE_UNCHANGED);
        repository.editAnnouncement(id,admin,title,body,input.category(),input.scope());return adminView(repository.announcement(id,false));
    }
    @Transactional
    @cn.xinkelink.audit.AuditAction(module="announcements",action="publish",targetType="announcement",fields={"status","version"})
    public Map<String,Object> publish(long id,ConfirmCommand input,AuthPrincipal viewer) {
        long admin=support.requireAdmin(viewer);OperationsSupport.confirm(input.expectedVersion(),input.confirmed());var row=available(repository.announcement(id,true));
        // 网络重试可能带首次发布前的版本；已发布保持幂等，不再生成通知。
        if("published".equals(row.status())){cn.xinkelink.audit.AuditChanges.mark(false);return adminView(row);}
        ContentSupport.version(row.version(),input.expectedVersion());
        repository.publish(id,admin);
        cn.xinkelink.audit.AuditChanges.mark(true);
        if(row.firstPublishedAt()==null)notifications.announcement(id);
        return adminView(repository.announcement(id,false));
    }
    @Transactional
    @cn.xinkelink.audit.AuditAction(module="announcements",action="withdraw",targetType="announcement",fields={"status","version"})
    public Map<String,Object> withdraw(long id,ConfirmCommand input,AuthPrincipal viewer) {
        long admin=support.requireAdmin(viewer);OperationsSupport.confirm(input.expectedVersion(),input.confirmed());var row=available(repository.announcement(id,true));
        if(!"published".equals(row.status()))throw new BusinessException(ErrorCode.STATE_CONFLICT);
        ContentSupport.version(row.version(),input.expectedVersion());repository.withdraw(id,admin);return adminView(repository.announcement(id,false));
    }
    @Transactional
    @cn.xinkelink.audit.AuditAction(module="announcements",action="delete",targetType="announcement",fields={"deletedAt","version"})
    public Map<String,Object> delete(long id,String ifMatch,AuthPrincipal viewer) {
        long admin=support.requireAdmin(viewer),expected=ContentSupport.ifMatch(ifMatch);var row=available(repository.announcement(id,true));
        ContentSupport.version(row.version(),expected);repository.deleteAnnouncement(id,admin);return map("deleted",true);
    }
    private AnnouncementRow available(AnnouncementRow row) { if(row==null||row.deletedAt()!=null)throw unavailable();return row; }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.TARGET_UNAVAILABLE); }
    private Map<String,Object> publicView(AnnouncementRow row) { return map("id",Long.toString(row.id()),"title",row.title(),"body",row.body(),"category",row.category(),"scope",row.scope(),"status",row.status(),"publishedAt",row.lastPublishedAt()); }
    private Map<String,Object> adminView(AnnouncementRow row) { return adminView(row,repository.adminNames(new HashSet<>(List.of(row.createdBy(),row.updatedBy())))); }
    private Map<String,Object> adminView(AnnouncementRow row,Map<Long,String> names) {
        Map<String,Object> result=publicView(row);result.putAll(map("createdAt",row.createdAt(),"updatedAt",row.updatedAt(),"firstPublishedAt",row.firstPublishedAt(),"lastPublishedAt",row.lastPublishedAt(),"withdrawnAt",row.withdrawnAt(),
                "creator",map("id",Long.toString(row.createdBy()),"displayName",names.get(row.createdBy())),"updater",map("id",Long.toString(row.updatedBy()),"displayName",names.get(row.updatedBy())),"version",row.version()));return result;
    }
}
