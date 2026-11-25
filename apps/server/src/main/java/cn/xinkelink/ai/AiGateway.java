package cn.xinkelink.ai;
import cn.xinkelink.common.api.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.ai.openai.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;import java.net.http.*;import java.time.Duration;import java.util.*;
import static cn.xinkelink.ai.AiRuntime.Stage.*;
/** OpenAI chat/embedding; explicitly selected Cohere or native DashScope rerank protocol. Never logs provider bodies/keys. */
@Component @Profile("auth")
public class AiGateway {
 private final Environment env;
 private final JsonMapper json=JsonMapper.builder().build();
 private final HttpClient http;
 private final AiRuntime runtime;
 private final AiUsage usage;
 public AiGateway(Environment env){this(env,null,null);}
 @Autowired public AiGateway(Environment env, AiRuntime runtime, AiUsage usage){
  this.env=env;this.runtime=runtime;this.usage=usage;
  http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 public int setting(String key,int fallback){return env.getProperty("xinkelink.ai."+key,Integer.class,fallback);}
 public void requireEnabled(){if(runtime!=null)runtime.requireEnabled();}
 private AiRuntime.Provider provider(AiRuntime.Stage stage){
  requireEnabled();
  var provider=runtime==null?AiRuntime.defaults(env,stage):runtime.provider(stage);
  if(provider.baseUrl().isBlank()||provider.model().isBlank()||provider.key().isBlank())throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
  return provider;
 }
 public String chat(String system,String user){
  return chat(system,user,provider(CHAT));
 }
 private String chat(String system,String user,AiRuntime.Provider provider){
  return measure(CHAT,provider,metrics->{
   var response=chatClient(provider).call(new Prompt(List.of(new SystemMessage(system),new UserMessage(user))));
   metrics.tokens=tokens(response.getMetadata().getUsage());
   var generation=response.getResult();
   String result=generation.getOutput().getText();
   if("length".equalsIgnoreCase(generation.getMetadata().getFinishReason())||result==null||result.isBlank()||result.length()>setting("output-chars",8000))throw new IllegalStateException();return result;
  });
 }
 public float[] embed(String text){
  return embed(text,provider(EMBEDDING));
 }
 public float[] embed(String text, AiRuntime.Provider provider){
  requireEnabled();
  return measure(EMBEDDING,provider,metrics->{
   var response=embeddingClient(provider).embedForResponse(List.of(text));
   metrics.tokens=tokens(response.getMetadata().getUsage());
   float[] vector=response.getResult().getOutput();
   if(vector.length==0||vector.length>8192)throw new IllegalStateException();for(float f:vector)if(!Float.isFinite(f))throw new IllegalStateException();return vector;
  });
 }
 public List<Double> rerank(String query,List<String> texts){
  return rerank(query,texts,provider(RERANK));
 }
 private List<Double> rerank(String query,List<String> texts,AiRuntime.Provider provider){
  return measure(RERANK,provider,metrics->{
   String protocol=provider.protocol();
   if(!Set.of("cohere","dashscope").contains(protocol))throw new IllegalStateException();
   Map<String,Object> body=protocol.equals("dashscope")
    ?Map.of("model",provider.model(),"input",Map.of("query",query,"documents",texts),"parameters",Map.of("top_n",texts.size(),"return_documents",false))
    :Map.of("model",provider.model(),"query",query,"documents",texts,"top_n",texts.size());
   var request=HttpRequest.newBuilder(URI.create(provider.baseUrl())).timeout(Duration.ofSeconds(setting("timeout-seconds",25)))
    .header("Authorization","Bearer "+provider.key()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
   var pending=http.sendAsync(request,ignored->new BoundedBody());
   HttpResponse<byte[]> response;
   try{response=pending.get(Math.clamp(setting("timeout-seconds",25),1,120),java.util.concurrent.TimeUnit.SECONDS);}finally{if(!pending.isDone())pending.cancel(true);}
   {
    if(response.statusCode()!=200)throw new IllegalStateException();byte[] bytes=response.body();
    var root=json.readTree(bytes);
    var reported=root.path("usage").path("total_tokens");
    if(reported.isIntegralNumber()&&reported.canConvertToLong()&&reported.asLong()>=0)metrics.tokens=reported.asLong();
    var node=protocol.equals("dashscope")?root.path("output").get("results"):root.get("results");if(node==null||!node.isArray()||node.size()!=texts.size())throw new IllegalStateException();
    Double[] scores=new Double[texts.size()];for(var row:node){
     var index=row.path("index");var relevance=row.path("relevance_score");
     if(!index.isIntegralNumber()||!index.canConvertToInt()||!relevance.isNumber())throw new IllegalStateException();
     int i=index.intValue();double score=relevance.doubleValue();if(i<0||i>=scores.length||scores[i]!=null||!Double.isFinite(score))throw new IllegalStateException();scores[i]=score;
    }
    return List.of(scores);
   }
  });
 }
 void check(AiRuntime.Stage stage,AiRuntime.Provider provider){
  requireEnabled();
  switch(stage){
   case CHAT -> chat("连接测试。只回复 OK。","测试",provider);
   case EMBEDDING -> embed("连接测试",provider);
   case RERANK -> rerank("图书馆",List.of("图书馆开放时间","校园操场"),provider);
  }
 }
 private record Connection<T>(AiRuntime.Provider provider,int timeout,int output,T client){}
 private volatile Connection<OpenAiChatModel> chat;
 private volatile Connection<OpenAiEmbeddingModel> embeddings;

 private OpenAiChatModel chatClient(AiRuntime.Provider provider){
  int timeout=setting("timeout-seconds",25), output=setting("output-tokens",1200);
  var existing=chat;
  if(existing!=null&&existing.provider().equals(provider)&&existing.timeout()==timeout&&existing.output()==output)return existing.client();
  var options=OpenAiChatOptions.builder().baseUrl(provider.baseUrl()).apiKey(provider.key()).model(provider.model())
   .timeout(Duration.ofSeconds(timeout)).maxRetries(0).maxTokens(output);
  if(provider.thinking()!=null)options.extraBody(Map.of("enable_thinking",provider.thinking()));
  var client=OpenAiChatModel.builder().options(options.build()).build();
  chat=new Connection<>(provider,timeout,output,client);
  return client;
 }

 private OpenAiEmbeddingModel embeddingClient(AiRuntime.Provider provider){
  int timeout=setting("timeout-seconds",25);
  var existing=embeddings;
  if(existing!=null&&existing.provider().equals(provider)&&existing.timeout()==timeout)return existing.client();
  var options=OpenAiEmbeddingOptions.builder().baseUrl(provider.baseUrl()).apiKey(provider.key()).model(provider.model())
   .timeout(Duration.ofSeconds(timeout)).maxRetries(0).build();
  var client=new OpenAiEmbeddingModel(options);
  embeddings=new Connection<>(provider,timeout,0,client);
  return client;
 }

 private static Long tokens(Usage usage){
  if(usage==null||usage instanceof EmptyUsage)return null;
  Integer count=usage.getTotalTokens();
  return count==null||count<0?null:count.longValue();
 }

 private static final class Measurement { Long tokens; }
 @FunctionalInterface private interface Exchange<T>{ T run(Measurement metrics) throws Exception; }
 private <T> T measure(AiRuntime.Stage stage,AiRuntime.Provider provider,Exchange<T> exchange){
  var started=usage==null?null:usage.now();long nanos=System.nanoTime();
  var metrics=new Measurement();boolean success=false;
  try{
   T result=exchange.run(metrics);success=true;return result;
  }catch(InterruptedException e){
   Thread.currentThread().interrupt();throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
  }catch(Exception e){throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}
  finally{if(usage!=null)usage.call(stage,provider.model(),started,nanos,success,metrics.tokens);}
 }
 private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final java.util.concurrent.CompletableFuture<byte[]> body=new java.util.concurrent.CompletableFuture<>();
  private final java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();private java.util.concurrent.Flow.Subscription subscription;
  @Override public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
  @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
  @Override public void onNext(List<java.nio.ByteBuffer> buffers){
   for(var buffer:buffers){if(output.size()+buffer.remaining()>262144){subscription.cancel();body.completeExceptionally(new IllegalStateException("Response exceeds bound"));return;}
    byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);output.writeBytes(bytes);
   }subscription.request(1);
  }
  @Override public void onError(Throwable failure){body.completeExceptionally(failure);}
  @Override public void onComplete(){body.complete(output.toByteArray());}
 }
}
