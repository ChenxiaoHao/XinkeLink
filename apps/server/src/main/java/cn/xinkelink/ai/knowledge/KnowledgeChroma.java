package cn.xinkelink.ai.knowledge;

import cn.xinkelink.common.api.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

/** Chroma v2 wire adapter; always supplies external API embeddings explicitly. */
@Component @Profile("auth")
public class KnowledgeChroma {
 private final String base,path;private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
 private final JsonMapper json=JsonMapper.builder().build();
 public KnowledgeChroma(@Value("${xinkelink.ai.chroma.url:}")String base,@Value("${xinkelink.ai.chroma.tenant:default_tenant}")String tenant,@Value("${xinkelink.ai.chroma.database:default_database}")String database){
  this.base=base.replaceAll("/+$", "");if(!tenant.matches("[a-zA-Z0-9_-]{1,80}")||!database.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid Chroma namespace");
  path="/api/v2/tenants/"+tenant+"/databases/"+database+"/collections";
 }
 String prepare(List<KnowledgeService.Chunk> chunks){
  long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
  var created=call("POST",path,Map.of("name","xl-"+UUID.randomUUID(),"metadata",Map.of("purpose","link-ai"),"get_or_create",false));String id=created.path("id").asText();
  if(id.isBlank())throw unavailable();
  for(int start=0;start<chunks.size();start+=50){if(System.nanoTime()>deadline)throw unavailable();var batch=chunks.subList(start,Math.min(chunks.size(),start+50));
   call("POST",path+"/"+id+"/add",Map.of("ids",batch.stream().map(KnowledgeService.Chunk::id).toList(),"embeddings",batch.stream().map(KnowledgeService.Chunk::embedding).toList(),"documents",batch.stream().map(KnowledgeService.Chunk::text).toList(),"metadatas",batch.stream().map(c->Map.of("version_id",c.versionId())).toList()));
  }verify(id,chunks.size());return id;
 }
 void verify(String collection,int minimum){
  var count=call("GET",path+"/"+safeId(collection)+"/count",null);if(!count.isIntegralNumber()||count.asInt()<minimum)throw unavailable();
 }
 List<String> search(String collection,float[] vector,Set<String> versionIds,int limit){
  if(versionIds.isEmpty())return List.of();
  var result=call("POST",path+"/"+safeId(collection)+"/query",Map.of("query_embeddings",List.of(vector),"n_results",limit,"where",Map.of("version_id",Map.of("$in",versionIds)),"include",List.of("distances")));
  var ids=result.path("ids");if(!ids.isArray()||ids.isEmpty()||!ids.get(0).isArray())throw unavailable();
  List<String> out=new ArrayList<>();for(var id:ids.get(0)){if(!id.isString())throw unavailable();out.add(id.asText());}return out;
 }
 private String safeId(String id){if(id==null||!id.matches("[a-zA-Z0-9_-]{1,100}"))throw unavailable();return id;}
 private tools.jackson.databind.JsonNode call(String method,String endpoint,Object body){
  if(base.isBlank())throw unavailable();
  try {var request=HttpRequest.newBuilder(URI.create(base+endpoint)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
   request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
   var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()<200||response.statusCode()>299||response.body().length()>2000000)throw unavailable();return response.body().isBlank()?json.nullNode():json.readTree(response.body());
  }catch(BusinessException ex){throw ex;}catch(InterruptedException ex){Thread.currentThread().interrupt();throw unavailable();}catch(Exception ex){throw unavailable();}
 }
 private static BusinessException unavailable(){return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}
}
