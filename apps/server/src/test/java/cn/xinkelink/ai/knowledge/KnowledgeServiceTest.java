package cn.xinkelink.ai.knowledge;
import cn.xinkelink.ai.AiGateway;
import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class KnowledgeServiceTest {
 final JdbcTemplate jdbc=mock(JdbcTemplate.class);final AiGateway gateway=mock(AiGateway.class);final ContentSupport support=mock(ContentSupport.class);final KnowledgeChroma chroma=mock(KnowledgeChroma.class);
 final Clock clock=mock(Clock.class);
 KnowledgeService service(){when(clock.instant()).thenReturn(Instant.parse("2026-11-22T00:00:00Z"));return new KnowledgeService(jdbc,support,gateway,chroma,mock(PlatformTransactionManager.class),clock,30,20,5,0);}
 @Test void noEvidenceCannotProduceAnAnswer(){
  var service=service();service.restore();var result=service.searchKnowledge("休学怎么办","all");
  assertEquals("no_candidates",result.status());assertTrue(result.sources().isEmpty());verifyNoInteractions(gateway);
 }
 void stored(String scope)throws Exception{
  when(gateway.setting("context-chars",6000)).thenReturn(6000);
  var rs=mock(ResultSet.class);when(rs.getString("id")).thenReturn("chunk-1");when(rs.getString("version_id")).thenReturn("version-1");when(rs.getString("body")).thenReturn("第一条 申请休学需要提交申请表。疾病例外由代理人办理。");when(rs.getString("indexed_embedding")).thenReturn("[1,0]");when(rs.getLong("document_id")).thenReturn(1L);
  when(rs.getString("title")).thenReturn("学籍制度");when(rs.getString("source")).thenReturn("https://hbcet.hpu.edu.cn/test.htm");when(rs.getString("document_version")).thenReturn("v1");when(rs.getString("published_date")).thenReturn("2026-11-17");when(rs.getString("scope")).thenReturn(scope);when(rs.getString("validity")).thenReturn("管理员确认当前适用");when(rs.getString("section_title")).thenReturn("第一条");when(rs.getString("page")).thenReturn("1");
  when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<KnowledgeService.Chunk>>any())).thenAnswer(i->List.of(i.<RowMapper<KnowledgeService.Chunk>>getArgument(1).mapRow(rs,0)));
  when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("collection-old"));
  when(gateway.embed(anyString())).thenReturn(new float[]{1,0});when(chroma.search(anyString(),any(),anySet(),anyInt())).thenReturn(List.of("chunk-1"));when(gateway.rerank(anyString(),anyList())).thenReturn(List.of(.8));
 }
 @Test void restoreReconstructsEvidenceFromSqlAndChecksChroma()throws Exception{stored("all");var s=service();s.restore();when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"sufficient\",\"evidenceIds\":[\"1\"]}","需要申请表[1]");var result=s.searchKnowledge("申请休学","all");assertEquals("answered",result.status());assertEquals("v1",result.sources().getFirst().get("version"));assertEquals("1",result.sources().getFirst().get("page"));verify(chroma).verify("collection-old",1);}
 @Test void scopeMismatchNeverLeaksEvidence()throws Exception{stored("2024级");var s=service();s.restore();assertEquals("no_candidates",s.searchKnowledge("申请休学","all").status());verifyNoInteractions(gateway);}
 @Test void unavailableChromaAtRestartIsRetryableNotNoAnswer()throws Exception{stored("all");doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(chroma).verify(anyString(),anyInt());var s=service();s.restore();assertEquals(ErrorCode.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,()->s.searchKnowledge("休学","all")).code());}
 @Test void rerankerFailureCannotBeSilentlyBypassed()throws Exception{stored("all");var s=service();s.restore();when(gateway.rerank(anyString(),anyList())).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));assertThrows(BusinessException.class,()->s.searchKnowledge("休学","all"));verify(gateway,never()).chat(anyString(),anyString());}
 @Test void unrelatedEvidenceDoesNotReachAnswerGenerator()throws Exception{stored("all");var s=service();s.restore();when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"irrelevant\",\"evidenceIds\":[]}");assertEquals("low_relevance",s.searchKnowledge("休学","all").status());verify(gateway,times(1)).chat(anyString(),anyString());}
 @Test void incompleteEvidenceDoesNotReachAnswerGenerator()throws Exception{stored("all");var s=service();s.restore();when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"incomplete\",\"evidenceIds\":[]}");assertEquals("incomplete_evidence",s.searchKnowledge("休学","all").status());verify(gateway,times(1)).chat(anyString(),anyString());}
 @Test void conflictingVersionsRequireAdministrator()throws Exception{stored("all");var s=service();s.restore();when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"conflict\",\"evidenceIds\":[]}");assertEquals("version_conflict",s.searchKnowledge("休学","all").status());}
 @Test void fabricatedCitationIsRejected()throws Exception{stored("all");var s=service();s.restore();when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"sufficient\",\"evidenceIds\":[\"1\"]}","直接办理[99]");assertThrows(BusinessException.class,()->s.searchKnowledge("休学","all"));}
 @Test void partialAnswerExplicitlyStatesMissingEvidence()throws Exception{stored("all");var s=service();s.restore();when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"partial\",\"evidenceIds\":[\"1\"]}","申请表[1]");var result=s.searchKnowledge("休学","all");assertEquals("partial",result.status());assertTrue(result.text().contains("尚缺少充分证据"));}
 @Test void oversizedSerializedEvidenceRefusesBeforeGateOrAnswerWithoutTruncation()throws Exception{
  stored("all");when(gateway.setting("context-chars",6000)).thenReturn(300);
  when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"sufficient\",\"evidenceIds\":[\"1\"]}","申请表[1]");
  var s=service();s.restore();var result=s.searchKnowledge("休学","all");
  assertEquals("incomplete_evidence",result.status());assertTrue(result.text().contains("缩小"));assertTrue(result.sources().isEmpty());verify(gateway,never()).chat(anyString(),anyString());
 }
 @Test void failedNewChromaGenerationNeverSwitchesArchivePointer()throws Exception{
  stored("all");var s=service();s.restore();savedDraft();when(chroma.prepare(anyList())).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
  assertThrows(BusinessException.class,()->s.publish(1L,new AuthPrincipal(1,1,"admin")));verify(jdbc,never()).update(anyString(),org.mockito.ArgumentMatchers.<Object[]>any());
  when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"sufficient\",\"evidenceIds\":[\"1\"]}","仍按旧版申请表办理[1]");assertEquals("v1",s.searchKnowledge("休学","all").sources().getFirst().get("version"));
 }
 @Test void recoversPublishedIndexAfterChromaWasUnavailableDuringStartup() throws Exception {
  stored("all");
  doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).doNothing().when(chroma).verify("collection-old",1);
  var service=service();
  var admin=new AuthPrincipal(1,1,"admin");
  service.restore();
  assertEquals(false,service.list(admin).get("ready"));
  assertEquals(ErrorCode.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,()->service.searchKnowledge("休学","all")).code());
  verify(chroma,times(1)).verify("collection-old",1);
  when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"sufficient\",\"evidenceIds\":[\"1\"]}","仍按旧版申请表办理[1]");
  when(clock.instant()).thenReturn(Instant.parse("2026-11-22T00:01:00Z"));
  assertEquals(true,service.list(admin).get("ready"));
  assertEquals("v1",service.searchKnowledge("休学","all").sources().getFirst().get("version"));
  verify(chroma,times(2)).verify("collection-old",1);
 }
 @Test void readsPublishedIndexWhileReplacementPreparationIsBlocked() throws Exception {
  stored("all");
  savedDraft();
  var service=service();
  service.restore();
  var preparing=new CountDownLatch(1);
  var finishPreparation=new CountDownLatch(1);
  when(chroma.prepare(anyList())).thenAnswer(call->{
   preparing.countDown();
   assertTrue(finishPreparation.await(10,TimeUnit.SECONDS));
   throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
  });
  when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"sufficient\",\"evidenceIds\":[\"1\"]}","仍按旧版申请表办理[1]");
  try(var tasks=Executors.newVirtualThreadPerTaskExecutor()){
   var publication=tasks.submit(()->service.publish(1L,new AuthPrincipal(1,1,"admin")));
   try{
    assertTrue(preparing.await(5,TimeUnit.SECONDS));
    var query=tasks.submit(()->service.searchKnowledge("休学","all"));
    assertEquals("v1",query.get(2,TimeUnit.SECONDS).sources().getFirst().get("version"));
   }finally{finishPreparation.countDown();}
   assertInstanceOf(BusinessException.class,assertThrows(ExecutionException.class,()->publication.get(5,TimeUnit.SECONDS)).getCause());
  }
 }
 @Test void disablingTheSourceDiscardsAnAnswerStillBeingGenerated() throws Exception {
  stored("all");
  savedDraft();
  var service=service();
  service.restore();
  var answering=new CountDownLatch(1);
  var finishAnswer=new CountDownLatch(1);
  when(gateway.chat(anyString(),anyString())).thenReturn("{\"status\":\"sufficient\",\"evidenceIds\":[\"1\"]}").thenAnswer(call->{
   answering.countDown();
   assertTrue(finishAnswer.await(10,TimeUnit.SECONDS));
   return "需要提交申请表[1]";
  });
  try(var tasks=Executors.newVirtualThreadPerTaskExecutor()){
   var query=tasks.submit(()->service.searchKnowledge("休学","all"));
   try{
    assertTrue(answering.await(5,TimeUnit.SECONDS));
    tasks.submit(()->service.disable(1L,new AuthPrincipal(1,1,"admin"))).get(2,TimeUnit.SECONDS);
    assertEquals("no_candidates",service.searchKnowledge("休学","all").status());
   }finally{finishAnswer.countDown();}
   var failure=assertThrows(ExecutionException.class,()->query.get(5,TimeUnit.SECONDS));
   assertEquals(ErrorCode.SERVICE_UNAVAILABLE,assertInstanceOf(BusinessException.class,failure.getCause()).code());
  }
 }
 private void savedDraft(){
  Map<String,Object> row=Map.of("id",1L,"title","学籍制度","source","https://hbcet.hpu.edu.cn/test.htm","document_version","v2","scope","all","validity","当前适用","body","第一条 申请休学需要新表。","reviewed",true);
  when(jdbc.queryForList(eq("SELECT * FROM xl_ai_knowledge WHERE id=?"),eq(1L))).thenReturn(List.of(row));
 }
}
