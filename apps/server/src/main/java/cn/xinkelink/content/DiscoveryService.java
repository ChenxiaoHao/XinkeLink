package cn.xinkelink.content;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.auth.persistence.UserAccount;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.CursorPage;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.forum.ForumService;
import cn.xinkelink.media.MediaService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.xinkelink.content.ContentSupport.map;

@Service
@Profile("auth")
@Transactional(readOnly=true)
public class DiscoveryService {
    private final ContentMapper mapper;
    private final AuthMapper accounts;
    private final ContentSupport support;
    private final ContentCursor cursors;
    private final ForumService forum;
    private final MediaService media;
    public DiscoveryService(ContentMapper mapper,AuthMapper accounts,ContentSupport support,
            ContentCursor cursors,ForumService forum,MediaService media){
        this.mapper=mapper;this.accounts=accounts;this.support=support;this.cursors=cursors;this.forum=forum;this.media=media;
    }

    public Map<String,Object> search(String q,String type,String category,String sort,String filter,String cursor,Integer size,AuthPrincipal viewer){
        String keyword=ContentSupport.keyword(q);

        if(keyword.isEmpty()||!"posts".equals(type)||filter!=null)throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return map("type",type,"q",keyword,"result",forum.list(category,sort==null?"latest":sort,keyword,cursor,size,viewer));
    }

    public CursorPage<Map<String,Object>> favorites(String type,String cursor,Integer size,AuthPrincipal viewer){
        return savedItems(type,cursor,size,viewer,false);
    }
    public CursorPage<Map<String,Object>> likes(String type,String cursor,Integer size,AuthPrincipal viewer){
        return savedItems(type,cursor,size,viewer,true);
    }
    private CursorPage<Map<String,Object>> savedItems(String type,String cursor,Integer size,AuthPrincipal viewer,boolean likes){
        long user=support.require(viewer,null);
        if(!Set.of("post").contains(type==null?"":type))throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String scope=(likes?"likes:":"favorites:")+type;
        var position=cursors.read(cursor,scope,user);
        int limit=ContentSupport.size(size);
        var rows=likes?mapper.likes(user,type,position.snapshot(),position.at(),position.id(),limit+1)
                :mapper.favorites(user,type,position.snapshot(),position.at(),position.id(),limit+1);
        boolean more=rows.size()>limit;
        if(more)rows=rows.subList(0,limit);
        List<Long> ids=rows.stream().map(ContentMapper.FavoriteRow::id).toList();
        // 使用同一业务投影和批量查询；收藏关系不成为读取治理内容的旁路。
        var items=forum.favoriteItems(ids,viewer);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows){
            Object item=items.get(row.id());
            boolean available=item!=null;
            result.add(map("type",type,"targetId",Long.toString(row.id()),likes?"likedAt":"favoritedAt",row.createdAt(),
                    "availability",available?"available":"unavailable","item",item));
        }
        String next=null;
        if(more){var last=rows.getLast();next=cursors.next(scope,user,new ContentCursor.Position(position.snapshot(),last.createdAt(),last.id(),null));}
        return new CursorPage<>(result,next,more);
    }

    public Map<String,Object> summary(AuthPrincipal viewer){return mapper.summary(support.require(viewer,null));}
    public Map<String,Object> profile(long userId){
        UserAccount user=publicUser(userId);
        // 与帖子作者共用头像投影，返回可访问的短期地址，不暴露存储路径。
        return map("id",Long.toString(user.id()),"nickname",user.nickname(),"avatar",media.avatars(List.of(userId)).get(userId),"bio",user.bio(),
                "major",user.major(),"className",user.className(),"enrollmentYear",user.enrollmentYear(),
                "selfReported",true,"stats",mapper.publicStats(userId));
    }
    public CursorPage<Map<String,Object>> authorPosts(long user,String cursor,Integer size,AuthPrincipal viewer){return authorContent(user,cursor,size,viewer);}

    private CursorPage<Map<String,Object>> authorContent(long user,String cursor,Integer size,AuthPrincipal viewer){
        publicUser(user);
        String scope="author:posts:"+user;
        Long viewerId=support.viewer(viewer);
        var position=cursors.read(cursor,scope,viewerId);
        int limit=ContentSupport.size(size);
        var rows=mapper.authorPosts(user,position.snapshot(),position.at(),position.id(),limit+1);
        boolean more=rows.size()>limit;
        if(more)rows=rows.subList(0,limit);
        var ids=rows.stream().map(ContentMapper.Entry::id).toList();
        var cards=forum.favoriteItems(ids,viewer);
        List<Map<String,Object>> result=rows.stream().map(row->cards.get(row.id())).filter(Objects::nonNull).toList();
        String next=null;
        if(more){var last=rows.getLast();next=cursors.next(scope,viewerId,new ContentCursor.Position(position.snapshot(),last.createdAt(),last.id(),null));}
        return new CursorPage<>(result,next,more);
    }
    private UserAccount publicUser(long id){
        UserAccount user=accounts.findUser(id);
        if(user==null||user.deletedAt()!=null)throw new BusinessException(ErrorCode.TARGET_UNAVAILABLE);
        return user;
    }
}
