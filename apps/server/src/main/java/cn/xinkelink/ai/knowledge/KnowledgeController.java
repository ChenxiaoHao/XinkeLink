package cn.xinkelink.ai.knowledge;
import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.content.ContentSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@RestController @Profile("auth") @RequestMapping("/api/v1/admin/ai/knowledge")
public class KnowledgeController {
 private final KnowledgeService knowledge;private final KnowledgeImport importer;private final ContentSupport support;
 public KnowledgeController(KnowledgeService knowledge,KnowledgeImport importer,ContentSupport support){this.knowledge=knowledge;this.importer=importer;this.support=support;}
 @GetMapping public ApiResponse<Map<String,Object>> list(@AuthenticationPrincipal AuthPrincipal admin){return ApiResponse.success(knowledge.list(admin));}
 @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody KnowledgeService.Draft body,@AuthenticationPrincipal AuthPrincipal admin){return ApiResponse.success(knowledge.create(body,admin));}
 @PutMapping("/{id}") public ApiResponse<Map<String,Object>> update(@PathVariable String id,@RequestBody KnowledgeService.Draft body,@AuthenticationPrincipal AuthPrincipal admin){return ApiResponse.success(knowledge.update(ContentSupport.id(id),body,admin));}
 @PostMapping("/{id}/publish") public ApiResponse<Map<String,Object>> publish(@PathVariable String id,@AuthenticationPrincipal AuthPrincipal admin){return ApiResponse.success(knowledge.publish(ContentSupport.id(id),admin));}
 @PostMapping("/{id}/disable") public ApiResponse<Map<String,Object>> disable(@PathVariable String id,@AuthenticationPrincipal AuthPrincipal admin){return ApiResponse.success(knowledge.disable(ContentSupport.id(id),admin));}
 @PostMapping(value="/extract",consumes="multipart/form-data") public ApiResponse<Map<String,Object>> extract(@RequestParam("file")MultipartFile file,@AuthenticationPrincipal AuthPrincipal admin){support.requireAdmin(admin);return ApiResponse.success(importer.extract(file));}
 public record ImportUrl(String url){}
 @PostMapping("/import-url") public ApiResponse<Map<String,Object>> importUrl(@RequestBody ImportUrl body,@AuthenticationPrincipal AuthPrincipal admin){support.requireAdmin(admin);return ApiResponse.success(importer.importUrl(body.url()));}
}
