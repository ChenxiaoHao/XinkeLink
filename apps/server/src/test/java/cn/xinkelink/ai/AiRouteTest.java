package cn.xinkelink.ai;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiRouteTest {
 @Test void nonToolRoutesMayOmitSearchTermsAndNullableIndexMeansNoReference(){
  assertEquals("clarify",AiRoute.parse("{\"intent\":\"clarify\",\"query\":\"\",\"resultIndex\":null}",2000).intent());
  assertEquals("out_of_scope",AiRoute.parse("{\"intent\":\"out_of_scope\"}",2000).intent());
  assertEquals(0,AiRoute.parse("{\"intent\":\"posts\",\"query\":\"自行车\",\"resultIndex\":null}",2000).resultIndex());
  assertThrows(RuntimeException.class,()->AiRoute.parse("{\"intent\":\"posts\",\"query\":\"\"}",2000));
 }
 @Test void rejectsNumericCoercionOverflowAndPostQueriesBeyondTheExistingForumContract(){
  for(String raw:java.util.List.of(
   "{\"intent\":\"posts\",\"query\":123}",
   "{\"intent\":\"posts\",\"query\":\"手机\",\"days\":4294967303}",
   "{\"intent\":\"posts\",\"query\":\"手机\",\"resultIndex\":4294967297}",
   "{\"intent\":\"posts\",\"query\":\""+"中".repeat(61)+"\"}"))
   assertThrows(RuntimeException.class,()->AiRoute.parse(raw,2000),raw);
 }
 @Test void rejectsInventedRoutesAndConditions(){
  assertThrows(RuntimeException.class,()->AiRoute.parse("{\"intent\":\"sql\",\"query\":\"x\"}",2000));
  assertThrows(RuntimeException.class,()->AiRoute.parse("{\"intent\":\"posts\",\"query\":\"手机\",\"days\":-1}",2000));
  assertThrows(RuntimeException.class,()->AiRoute.parse("{\"intent\":\"posts\",\"query\":\"手机\",\"userId\":2}",2000));
 }
 @Test void acceptsBoundedPostConditionsAndClarification(){
  var route=AiRoute.parse("{\"intent\":\"posts\",\"query\":\"自行车\",\"days\":7,\"category\":\"secondhand\",\"resultIndex\":0}",2000);
  assertEquals(7,route.days()); assertEquals("自行车",route.query());
  assertEquals("clarify",AiRoute.parse("{\"intent\":\"clarify\",\"query\":\"请说明查什么\"}",2000).intent());
 }
 @Test void failsOnUnboundedOrMalformedOutput(){
  assertThrows(RuntimeException.class,()->AiRoute.parse("```json\n{}\n```",2000));
  assertThrows(RuntimeException.class,()->AiRoute.parse("{\"intent\":\"posts\",\"query\":\""+"中".repeat(201)+"\"}",2000));
 }
 @Test void acceptsPostSearchWithDateOrCategoryAndNoKeyword(){
  var recentCampus=AiRoute.parse("{\"intent\":\"posts\",\"query\":\"\",\"days\":7,\"category\":\"campus\"}",2000);
  assertEquals(7,recentCampus.days());
  assertEquals("campus",recentCampus.category());
  assertEquals("",recentCampus.query());
  assertEquals(7,AiRoute.parse("{\"intent\":\"posts\",\"days\":7}",2000).days());
  assertEquals("lost_found",AiRoute.parse("{\"intent\":\"posts\",\"category\":\"lost_found\"}",2000).category());
 }
 @Test void acceptsFollowUpWithoutRequiringThePreviousKeyword(){
  assertEquals(2,AiRoute.parse("{\"intent\":\"posts\",\"resultIndex\":2}",2000).resultIndex());
 }
}
