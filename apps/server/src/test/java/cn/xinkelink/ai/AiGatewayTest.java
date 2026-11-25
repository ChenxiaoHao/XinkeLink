package cn.xinkelink.ai;
import cn.xinkelink.common.api.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** HTTP STUBS: validates protocol/failures, never counts as real model acceptance. */
class AiGatewayTest {
 @Test void rejectsCoercedAndOverflowingRerankerFields(){
  for(String row:List.of("{\"index\":\"0\",\"relevance_score\":0.8}","{\"index\":0.5,\"relevance_score\":0.8}","{\"index\":4294967296,\"relevance_score\":0.8}","{\"index\":0,\"relevance_score\":\"0.8\"}")){
   response="{\"results\":["+row+"]}";assertThrows(RuntimeException.class,()->gateway.rerank("问",List.of("甲")),row);
  }
 }
 HttpServer server;MockEnvironment env;AiGateway gateway;String response;String requestBody;long bodyDelayMillis;int code=200;
 @BeforeEach void start() throws Exception {
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",ex->{requestBody=new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);byte[] bytes=response.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(code,bytes.length);if(bodyDelayMillis>0)try{Thread.sleep(bodyDelayMillis);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}ex.getResponseBody().write(bytes);ex.close();});server.start();
  String base="http://127.0.0.1:"+server.getAddress().getPort();env=new MockEnvironment();
  for(String kind:List.of("chat","embedding","rerank")){env.setProperty("xinkelink.ai."+kind+".url",base);env.setProperty("xinkelink.ai."+kind+".key","http-stub-only");env.setProperty("xinkelink.ai."+kind+".model","http-stub");}gateway=new AiGateway(env);
 }
 @AfterEach void stop(){server.stop(0);}
 @Test void reranksScoresByOriginalIndex(){response="{\"results\":[{\"index\":1,\"relevance_score\":0.8},{\"index\":0,\"relevance_score\":0.1}]}";assertEquals(List.of(0.1,0.8),gateway.rerank("问",List.of("甲","乙")));}
 @Test void rejectsMissingDuplicateAndInvalidRankScores(){for(String raw:List.of("{}","{\"results\":[]}","{\"results\":[{\"index\":0,\"relevance_score\":1},{\"index\":0,\"relevance_score\":1}]}")){response=raw;assertThrows(RuntimeException.class,()->gateway.rerank("问",List.of("甲","乙")));}}
 @Test void reportsProviderFailureAsRetryable(){response="private upstream error";code=429;assertThrows(RuntimeException.class,()->gateway.rerank("问",List.of("甲")));}
 @Test void embedsViaSpringAiExternalProtocol(){response="{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],\"model\":\"http-stub\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";assertArrayEquals(new float[]{0.1f,0.2f},gateway.embed("学校"));}
 @Test void chatsViaSpringAiExternalProtocol(){response="{\"id\":\"stub\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"http-stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"HTTP桩\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";assertEquals("HTTP桩",gateway.chat("受控","查询"));}
 @Test void nativeDashscopeRerankerUsesInputAndParametersAndRestoresIndices(){
  env.setProperty("xinkelink.ai.rerank.protocol","dashscope");
  response="{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.92},{\"index\":0,\"relevance_score\":0.12}]},\"usage\":{\"total_tokens\":3}}";
  assertEquals(List.of(.12,.92),gateway.rerank("申请休学",List.of("甲条款","乙条款")));
  var sent=tools.jackson.databind.json.JsonMapper.builder().build().readTree(requestBody);
  assertEquals("申请休学",sent.path("input").path("query").asText());assertEquals(2,sent.path("input").path("documents").size());
  assertEquals(2,sent.path("parameters").path("top_n").asInt());assertFalse(sent.path("parameters").path("return_documents").asBoolean());assertFalse(sent.has("query"));
 }
 @Test void unknownRerankProtocolFailsClosed(){env.setProperty("xinkelink.ai.rerank.protocol","unknown");response="{\"results\":[{\"index\":0,\"relevance_score\":0.8}]}";assertThrows(RuntimeException.class,()->gateway.rerank("问",List.of("甲")));}
 @Test void rerankDeadlineIncludesSlowResponseBody(){
  env.setProperty("xinkelink.ai.timeout-seconds","1");bodyDelayMillis=2400;
  response="{\"results\":[{\"index\":0,\"relevance_score\":0.8}]}";
  assertThrows(RuntimeException.class,()->gateway.rerank("问",List.of("甲")));
 }
 @Test void nativeDashscopeMalformedIndexDoesNotPass(){env.setProperty("xinkelink.ai.rerank.protocol","dashscope");response="{\"output\":{\"results\":[{\"index\":\"0\",\"relevance_score\":0.8}]}}";assertThrows(RuntimeException.class,()->gateway.rerank("问",List.of("甲")));}
 private String chatResponse(String content,String reason){return "{\"id\":\"stub\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"http-stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""+content+"\"},\"finish_reason\":\""+reason+"\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";}
 @Test void sendsThinkingOptionOnlyWhenConfigured() {
  response=chatResponse("正常","stop");
  env.setProperty("xinkelink.ai.chat.enable-thinking", "");
  assertEquals("正常",gateway.chat("受控","查询"));
  assertFalse(requestBody.contains("enable_thinking"));

  env.setProperty("xinkelink.ai.chat.enable-thinking", "false");
  assertEquals("正常",gateway.chat("受控","查询"));
  var thinking = tools.jackson.databind.json.JsonMapper.builder().build().readTree(requestBody).path("enable_thinking");
  assertTrue(thinking.isBoolean());
  assertFalse(thinking.asBoolean());
 }
 @Test void recordsReportedTokensForAllThreeProviderStages() {
  var runtime=org.mockito.Mockito.mock(AiRuntime.class);
  var usage=org.mockito.Mockito.mock(AiUsage.class);
  var now=java.time.Instant.parse("2026-11-25T12:00:00Z");
  org.mockito.Mockito.when(usage.now()).thenReturn(now);
  env.setProperty("xinkelink.ai.rerank.protocol","dashscope");
  for(var stage:AiRuntime.Stage.values()){
   org.mockito.Mockito.when(runtime.provider(stage)).thenReturn(AiRuntime.defaults(env,stage));
  }
  gateway=new AiGateway(env,runtime,usage);
  response=chatResponse("正常","stop");
  assertEquals("正常",gateway.chat("受控","查询"));
  response="{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],\"model\":\"http-stub\",\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}";
  assertArrayEquals(new float[]{0.1f,0.2f},gateway.embed("学校"));
  response="{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.92}]},\"usage\":{\"total_tokens\":4}}";
  assertEquals(List.of(.92),gateway.rerank("问",List.of("甲")));
  long tokens=2;
  for(var stage:AiRuntime.Stage.values()){
   org.mockito.Mockito.verify(usage).call(org.mockito.Mockito.eq(stage),org.mockito.Mockito.eq("http-stub"),
       org.mockito.Mockito.eq(now),org.mockito.Mockito.anyLong(),org.mockito.Mockito.eq(true),org.mockito.Mockito.eq(tokens++));
  }
 }
 @Test void emptyFinalContentIsRetryableInsteadOfReasoningOnlySuccess(){response=chatResponse("正常","stop");assertEquals("正常",gateway.chat("受控","问题"));response=chatResponse("","stop");assertThrows(RuntimeException.class,()->gateway.chat("受控","问题"));}
 @Test void tokenTruncatedFinalContentIsRetryableInsteadOfPartialSuccess(){response=chatResponse("正常","stop");assertEquals("正常",gateway.chat("受控","问题"));response=chatResponse("看似完整的HTTP桩输出","length");assertThrows(RuntimeException.class,()->gateway.chat("受控","问题"));}
 @Test void missingProviderSettingsReturnAnActionableError(){
  env.setProperty("xinkelink.ai.chat.key"," ");
  env.setProperty("xinkelink.ai.embedding.model","");
  env.setProperty("xinkelink.ai.rerank.url","");
  assertEquals("AI_NOT_CONFIGURED",assertThrows(BusinessException.class,()->gateway.chat("规则","查询")).code().name());
  assertEquals("AI_NOT_CONFIGURED",assertThrows(BusinessException.class,()->gateway.embed("示例")).code().name());
  assertEquals("AI_NOT_CONFIGURED",assertThrows(BusinessException.class,()->gateway.rerank("查询",List.of("示例"))).code().name());
  assertNull(requestBody);
 }
}
