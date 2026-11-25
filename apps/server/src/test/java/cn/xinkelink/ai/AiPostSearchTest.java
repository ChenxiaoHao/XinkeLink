package cn.xinkelink.ai;
import cn.xinkelink.auth.AuthPrincipal;import cn.xinkelink.common.api.CursorPage;import cn.xinkelink.forum.ForumService;
import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class AiPostSearchTest {
 @Test void controlledConditionsFilterTimeAndPreserveExistingProjection(){
  var forum=mock(ForumService.class);var search=new AiPostSearch(forum);var user=new AuthPrincipal(8,12,"user");
  Map<String,Object> recent=Map.of("id","1","createdAt",Instant.now().minus(Duration.ofDays(2)),"content","自行车，未写价格","author",Map.of("nickname","匿名同学"));
  Map<String,Object> old=Map.of("id","2","createdAt",Instant.now().minus(Duration.ofDays(9)),"content","自行车");
  when(forum.list("secondhand","latest","自行车",null,50,user)).thenReturn(new CursorPage<>(List.of(recent,old),null,false));
  var result=search.searchPosts(new AiRoute("posts","自行车",7,"secondhand",0),user);
  assertEquals(List.of(recent),result);assertSame(recent,result.getFirst());assertFalse(result.getFirst().containsKey("price"));
 }
 @Test void historicalCardsUseCurrentPublicProjectionAndKeepSavedOrder(){
  var forum=mock(ForumService.class);var search=new AiPostSearch(forum);var user=new AuthPrincipal(8,12,"user");
  Map<String,Object> publicCard=Map.of("id","3","content","当前投影");
  when(forum.favoriteItems(List.of(2L,3L,1L),user)).thenReturn(Map.of(3L,publicCard));
  assertEquals(List.of(publicCard),search.current(List.of("2","3","1"),user));
  verify(forum).favoriteItems(List.of(2L,3L,1L),user);
 }
}
