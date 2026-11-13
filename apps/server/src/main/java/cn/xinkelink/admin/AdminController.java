package cn.xinkelink.admin;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.content.ContentSupport;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@Profile("auth")
public class AdminController {
    private final AdminQueryService queries;
    private final AdminCommandService commands;
    public AdminController(AdminQueryService queries,AdminCommandService commands){this.queries=queries;this.commands=commands;}
    @GetMapping("/dashboard") @Operation(summary="真实管理概览与未采集指标声明")
    ApiResponse<Map<String,Object>> dashboard(@RequestParam(required=false)String range,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.dashboard(range,admin));}
    @GetMapping("/posts") @Operation(summary="管理员帖子列表、筛选及全局统计")
    ApiResponse<Map<String,Object>> posts(@RequestParam Map<String,String> params,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.posts(params,admin));}
    @GetMapping("/posts/{postId}") @Operation(summary="管理员帖子详情及真实作者")
    ApiResponse<Map<String,Object>> post(@PathVariable String postId,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.post(ContentSupport.id(postId),admin));}
    @GetMapping("/posts/{postId}/comments") @Operation(summary="管理员帖子两级评论和已删占位")
    ApiResponse<Map<String,Object>> postComments(@PathVariable String postId,@RequestParam Map<String,String> params,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.postComments(ContentSupport.id(postId),params,admin));}
    @PostMapping("/posts/status") @Operation(summary="管理员整批隐藏或恢复帖子")
    ApiResponse<Map<String,Object>> postStatus(@RequestBody AdminModels.Batch body,@AuthenticationPrincipal AuthPrincipal admin){return ok(commands.postStatus(body,admin));}
    @DeleteMapping("/posts/{postId}") @Operation(summary="管理员按版本删除帖子与评论")
    ApiResponse<Map<String,Object>> deletePost(@PathVariable String postId,@RequestHeader(value="If-Match",required=false)String version,@AuthenticationPrincipal AuthPrincipal admin){return ok(commands.deletePost(ContentSupport.id(postId),ContentSupport.ifMatch(version),admin));}
    @GetMapping("/comments") @Operation(summary="管理员两级评论列表和有效可见统计")
    ApiResponse<Map<String,Object>> comments(@RequestParam Map<String,String> params,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.comments(params,admin));}
    @GetMapping("/comments/{commentId}/context") @Operation(summary="管理员评论上下文与签名游标回复")
    ApiResponse<Map<String,Object>> context(@PathVariable String commentId,@RequestParam Map<String,String> params,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.context(ContentSupport.id(commentId),params,admin));}
    @PostMapping("/comments/status") @Operation(summary="管理员整批隐藏或恢复评论")
    ApiResponse<Map<String,Object>> commentStatus(@RequestBody AdminModels.Batch body,@AuthenticationPrincipal AuthPrincipal admin){return ok(commands.commentStatus(body,admin));}
    @PostMapping("/comments/delete") @Operation(summary="管理员整批删除评论与关联回复")
    ApiResponse<Map<String,Object>> deleteComments(@RequestBody AdminModels.Batch body,@AuthenticationPrincipal AuthPrincipal admin){return ok(commands.deleteComments(body,admin));}
    @GetMapping("/users") @Operation(summary="管理员用户列表和有效账号状态筛选")
    ApiResponse<Map<String,Object>> users(@RequestParam Map<String,String> params,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.users(params,admin));}
    @GetMapping("/users/{userId}") @Operation(summary="管理员用户资料、有效限制与内容计数")
    ApiResponse<Map<String,Object>> user(@PathVariable String userId,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.user(ContentSupport.id(userId),admin));}
    @GetMapping("/users/{userId}/content") @Operation(summary="管理员用户内容与匿名真实归属")
    ApiResponse<Map<String,Object>> userContent(@PathVariable String userId,@RequestParam Map<String,String> params,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.userContent(ContentSupport.id(userId),params,admin));}
    @GetMapping("/users/{userId}/reports") @Operation(summary="管理员用户举报参与角色与安全摘要")
    ApiResponse<Map<String,Object>> userReports(@PathVariable String userId,@RequestParam Map<String,String> params,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.userReports(ContentSupport.id(userId),params,admin));}
    @GetMapping("/users/{userId}/moderation-history") @Operation(summary="管理员用户业务处置历史")
    ApiResponse<Map<String,Object>> userHistory(@PathVariable String userId,@RequestParam Map<String,String> params,@AuthenticationPrincipal AuthPrincipal admin){return ok(queries.userHistory(ContentSupport.id(userId),params,admin));}
    private ApiResponse<Map<String,Object>> ok(Map<String,Object> result){return ApiResponse.success(result);}
}
