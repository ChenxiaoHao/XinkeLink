package cn.xinkelink.ai;
import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.common.api.*;
import cn.xinkelink.forum.ForumService;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import java.time.*;import java.util.*;
/** search_posts: existing public query and projection are the authority for visibility/anonymous/media. */
@Service @Profile("auth")
public class AiPostSearch {
 private final ForumService forum;
 public AiPostSearch(ForumService forum){this.forum=forum;}
 public List<Map<String,Object>> searchPosts(AiRoute route,AuthPrincipal viewer){
  List<Map<String,Object>> result=new ArrayList<>();String cursor=null;
  Instant since=route.days()==null?null:Instant.now().minus(Duration.ofDays(route.days()));
  for(int page=0;page<10&&result.size()<20;page++){
   var data=forum.list(route.category(),"latest",route.query(),cursor,50,viewer);
   for(var row:data.items())if(since==null||Instant.parse(row.get("createdAt").toString()).compareTo(since)>=0)result.add(row);
   if(!data.hasMore())break;cursor=data.nextCursor();
  }
  return result.stream().limit(20).toList();
 }
 public List<Map<String,Object>> current(List<String> ids,AuthPrincipal viewer){
  var safe=forum.favoriteItems(ids.stream().map(ContentSupport::id).toList(),viewer);
  return ids.stream().map(id->safe.get(ContentSupport.id(id))).filter(Objects::nonNull).toList();
 }
}
