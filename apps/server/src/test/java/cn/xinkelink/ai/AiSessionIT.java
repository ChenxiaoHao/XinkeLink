package cn.xinkelink.ai;
import cn.xinkelink.auth.AuthPrincipal;import cn.xinkelink.common.api.*;
import cn.xinkelink.ai.knowledge.KnowledgeService;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.test.context.*;import org.springframework.test.context.bean.override.mockito.MockitoBean;import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
/** Real isolated MySQL, model is explicitly mocked; no real model acceptance claimed. */
@SpringBootTest @ActiveProfiles({"db","auth"})
class AiSessionIT {
 @Test void projectionFailureAfterCommitDoesNotRepeatCompletedModelWork(){
  when(ai.chat(anyString(),anyString())).thenReturn("{\"intent\":\"posts\",\"query\":\"自行车\"}");
  when(posts.searchPosts(any(),eq(a))).thenReturn(List.of(Map.of("id","24")));
  when(posts.current(anyList(),eq(a))).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
  assertThrows(BusinessException.class,()->service.send(session,"project","自行车",a));
  assertEquals("DONE",db.queryForObject("SELECT status FROM xl_ai_request WHERE session_id=?",String.class,session));
  doReturn(List.of(Map.of("id","24"))).when(posts).current(anyList(),eq(a));
  assertEquals(List.of(Map.of("id","24")),service.send(session,"project","自行车",a).get("posts"));
  verify(ai,times(1)).chat(anyString(),anyString());
 }
 @Test void revokedSessionCannotReadOrReplayCompletedPrivateHistory(){
  service.send(session,"done","问题",a);
  db.update("UPDATE xl_auth_session SET revoked_at=CURRENT_TIMESTAMP(3) WHERE id=?",a.sessionId());
  assertThrows(BusinessException.class,()->service.history(session,a));
  assertThrows(BusinessException.class,()->service.send(session,"done","问题",a));
  assertThrows(BusinessException.class,()->service.list(a));
 }
 @Test void boundedContextRemainsValidJsonAndKeepsActualResultIds(){
  when(ai.setting(eq("context-chars"),anyInt())).thenReturn(64);
  db.update("UPDATE xl_ai_session SET context_json=? WHERE id=?","{\"intent\":\"posts\",\"query\":\""+"自行车".repeat(40)+"\",\"days\":7,\"category\":null,\"resultIds\":[\"12\",\"24\"]}",session);
  when(ai.chat(anyString(),anyString())).thenAnswer(x->{
   String input=x.getArgument(1);assertTrue(input.contains("\"resultIds\":[\"12\",\"24\"]"));
   assertTrue(input.length()<400);return "{\"intent\":\"clarify\",\"query\":\"补充条件\"}";
  });
  assertEquals("DONE",service.send(session,"budget","第二条",a).get("status"));
 }
 @Test void restoredHistoryRebasesReferentsToTheCardsActuallyDisplayed(){
  db.update("UPDATE xl_ai_session SET context_json=? WHERE id=?","{\"intent\":\"posts\",\"query\":\"自行车\",\"days\":7,\"category\":null,\"resultIds\":[\"12\",\"24\"]}",session);
  db.update("INSERT INTO xl_ai_request(id,session_id,request_id,question,answer,status,sources_json,post_ids_json) VALUES(?,?,?,'自行车','找到帖子','DONE','[]','[\"12\",\"24\"]')",UUID.randomUUID().toString(),session,"original");
  when(posts.current(anyList(),eq(a))).thenReturn(List.of(Map.of("id","24")));
  var history=service.history(session,a);
  assertTrue(history.toString().contains("id=24"));
  when(ai.chat(anyString(),anyString())).thenAnswer(call->{
   assertTrue(call.<String>getArgument(1).contains("\"resultIds\":[\"24\"]"));
   return "{\"intent\":\"posts\",\"query\":\"自行车\",\"resultIndex\":1}";
  });
  assertEquals(List.of(Map.of("id","24")),service.send(session,"referent","查看第一条帖子",a).get("posts"));
  verify(posts,atLeastOnce()).current(List.of("24"),a);
  when(posts.current(anyList(),eq(a))).thenReturn(List.of());service.history(session,a);
  assertTrue(db.queryForObject("SELECT context_json FROM xl_ai_session WHERE id=?",String.class,session).contains("\"resultIds\":[]"));
  when(posts.current(anyList(),eq(a))).thenReturn(List.of(Map.of("id","24")));service.history(session,a);
  assertTrue(db.queryForObject("SELECT context_json FROM xl_ai_session WHERE id=?",String.class,session).contains("\"resultIds\":[\"24\"]"));
 }
 @Autowired AiSessionService service;@Autowired JdbcTemplate db;@MockitoBean AiGateway ai;@MockitoBean AiPostSearch posts;
 @MockitoSpyBean KnowledgeService knowledge;
 AuthPrincipal a,b;String session;
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("xinkelink.auth.jwt.secret-base64",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");}
 @BeforeEach void prepare(){
  when(ai.setting(anyString(),anyInt())).thenAnswer(x->x.getArgument(1));a=user();b=user();session=service.create(a,"测试").get("id").toString();
  when(ai.chat(anyString(),anyString())).thenReturn("{\"intent\":\"out_of_scope\",\"query\":\"写作\"}");
  when(posts.current(anyList(),any())).thenReturn(List.of());
 }
 AuthPrincipal user(){String key=UUID.randomUUID().toString();db.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('ai-http-stub',?,'AI隔离')",key);long uid=db.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='ai-http-stub' AND wechat_open_id=?",Long.class,key);db.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(?,256)),DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 HOUR))",uid,key);long sid=db.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",Long.class,uid);return new AuthPrincipal(uid,sid,"user");}
 @Test void guestAndOtherAccountsCannotReadSendOrDelete(){assertThrows(BusinessException.class,()->service.list(null));assertThrows(BusinessException.class,()->service.history(session,b));assertThrows(BusinessException.class,()->service.delete(session,b));assertThrows(BusinessException.class,()->service.send(session,"cross","问",b));}
 @Test void persistsContinuesDeduplicatesAndRejectsChangedPayload(){var first=service.send(session,"same","写一篇文章",a);assertEquals(first.get("id"),service.send(session,"same","写一篇文章",a).get("id"));assertThrows(BusinessException.class,()->service.send(session,"same","改文",a));assertEquals(1,((List<?>)service.history(session,a).get("messages")).size());verify(ai,times(1)).chat(anyString(),anyString());}
 @Test void deletesHistoryAndNeverResurrects(){service.send(session,"r1","写文章",a);service.delete(session,a);assertThrows(BusinessException.class,()->service.history(session,a));assertThrows(BusinessException.class,()->service.send(session,"r2","查帖子",a));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM xl_ai_request WHERE session_id=?",Integer.class,session));}
 @Test void failedModelCanRetrySameRequestWithoutDuplicate(){when(ai.chat(anyString(),anyString())).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));assertThrows(BusinessException.class,()->service.send(session,"retry","问题",a));doReturn("{\"intent\":\"clarify\",\"query\":\"什么制度\"}").when(ai).chat(anyString(),anyString());assertEquals("DONE",service.send(session,"retry","问题",a).get("status"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM xl_ai_request WHERE session_id=?",Integer.class,session));}
 @Test void invalidModelStructureIsRetryableFailure(){when(ai.chat(anyString(),anyString())).thenReturn("{\"intent\":\"sql\",\"query\":\"select *\"}");assertThrows(BusinessException.class,()->service.send(session,"bad","问题",a));assertEquals("ERROR",db.queryForObject("SELECT status FROM xl_ai_request WHERE session_id=?",String.class,session));}
 @Test void initialKnowledgeQuestionKeepsTheOriginalQualifiers() {
  String question = "在虚构测试样例中，图书馆期末周工作日几点闭馆？";
  String followUpQuery = "虚构测试样例中的图书馆期末周周末几点闭馆？";
  when(ai.chat(anyString(),anyString())).thenReturn(
      "{\"intent\":\"knowledge\",\"query\":\"图书馆期末周工作日闭馆时间\"}",
      "{\"intent\":\"knowledge\",\"query\":\"" + followUpQuery + "\"}");
  doReturn(new KnowledgeService.KnowledgeAnswer("虚构样例",List.of(),"answered"))
      .when(knowledge).searchKnowledge(anyString(),eq("all"));

  service.send(session,"demo-library",question,a);
  service.send(session,"demo-weekend","周末呢？",a);

  verify(knowledge).searchKnowledge(question,"all");
  verify(knowledge).searchKnowledge(followUpQuery,"all");
 }
 @Test void builtInRecentPostsPromptAcceptsAFilterOnlyResponse(){
  when(ai.chat(anyString(),anyString())).thenReturn("{\"intent\":\"posts\",\"query\":\"\",\"days\":7,\"category\":\"campus\"}");
  var route=new AiRoute("posts","",7,null,0);
  when(posts.searchPosts(route,a)).thenReturn(List.of(Map.of("id","42")));
  when(posts.current(List.of("42"),a)).thenReturn(List.of(Map.of("id","42")));
  var answer=service.send(session,"recent-campus","查找最近一周的校园帖子",a);
  assertEquals("DONE",answer.get("status"));
  assertEquals(List.of(Map.of("id","42")),answer.get("posts"));
  verify(posts).searchPosts(route,a);
 }
 @Test void aTopicDoesNotImplicitlyRestrictForumCategory(){
  doReturn("{\"intent\":\"posts\",\"query\":\"Java\",\"category\":\"study\"}").when(ai).chat(anyString(),anyString());
  var unrestricted=new AiRoute("posts","Java",null,null,0);
  var explicit=new AiRoute("posts","Java",null,"study",0);
  service.send(session,"java-anywhere","查找关于学习Java的帖子",a);
  service.send(session,"java-study","查找学习区里的 Java 帖子",a);
  verify(posts).searchPosts(unrestricted,a);
  verify(posts).searchPosts(explicit,a);
 }
 @Test void aCompleteRecentPostsQuestionStartsFreshButARefinementKeepsContext(){
  db.update("UPDATE xl_ai_session SET context_json=? WHERE id=?","{\"intent\":\"posts\",\"query\":\"Java\",\"category\":\"study\",\"resultIds\":[]}",session);
  when(ai.chat(anyString(),anyString())).thenAnswer(call->call.<String>getArgument(1).contains("\"context\":{}")
   ?"{\"intent\":\"posts\",\"query\":\"\",\"days\":7}"
   :"{\"intent\":\"posts\",\"query\":\"Java\",\"days\":7,\"category\":\"study\"}");
  service.send(session,"refinement","只看最近一周",a);
  service.send(session,"new-recent","最近一周发布的帖子",a);
  verify(posts).searchPosts(new AiRoute("posts","Java",7,"study",0),a);
  verify(posts).searchPosts(new AiRoute("posts","",7,null,0),a);
 }
 @Test void deletionWhileModelRunsCannotWriteLateAnswer() throws Exception {
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);when(ai.chat(anyString(),anyString())).thenAnswer(x->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return "{\"intent\":\"out_of_scope\",\"query\":\"文章\"}";});
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){var pending=executor.submit(()->service.send(session,"late","问题",a));assertTrue(entered.await(10,TimeUnit.SECONDS));service.delete(session,a);release.countDown();assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM xl_ai_request WHERE session_id=?",Integer.class,session));}
 }
 @Test void logoutWhileModelRunsCannotPersistAnswer(){when(ai.chat(anyString(),anyString())).thenAnswer(x->{db.update("UPDATE xl_auth_session SET revoked_at=CURRENT_TIMESTAMP(3) WHERE id=?",a.sessionId());return "{\"intent\":\"out_of_scope\",\"query\":\"文章\"}";});assertThrows(BusinessException.class,()->service.send(session,"logout","问题",a));assertNull(db.queryForObject("SELECT answer FROM xl_ai_request WHERE session_id=?",String.class,session));}
 @Test void overlappingSendsAreRejected() throws Exception {
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);when(ai.chat(anyString(),anyString())).thenAnswer(x->{entered.countDown();release.await(10,TimeUnit.SECONDS);return "{\"intent\":\"mixed\",\"query\":\"两种\"}";});
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){var first=executor.submit(()->service.send(session,"first","问题",a));assertTrue(entered.await(10,TimeUnit.SECONDS));assertThrows(BusinessException.class,()->service.send(session,"second","问题",a));release.countDown();assertEquals("DONE",first.get(10,TimeUnit.SECONDS).get("status"));}
 }
}
