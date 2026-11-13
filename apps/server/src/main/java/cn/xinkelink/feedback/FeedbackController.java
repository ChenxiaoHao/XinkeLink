package cn.xinkelink.feedback;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile("auth")
public class FeedbackController {
    private final JdbcTemplate jdbc;
    private final ContentSupport support;
    public FeedbackController(JdbcTemplate jdbc, ContentSupport support) { this.jdbc=jdbc; this.support=support; }
    record Input(String content, String clientRequestId) {}
    @PostMapping("/api/v1/feedback")
    public ApiResponse<Map<String,Object>> submit(@RequestBody Input input, @AuthenticationPrincipal AuthPrincipal actor) {
        long user=support.require(actor,null);
        if(input.content()==null || input.content().isBlank() || input.content().length()>1000 || input.clientRequestId()==null || !input.clientRequestId().matches("[a-zA-Z0-9-]{1,64}")) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String content=input.content().strip();
        // 同一次网络重试只保存一条，不允许用相同请求编号覆盖内容。
        jdbc.update("INSERT INTO xl_feedback(user_id,content,client_request_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",user,content,input.clientRequestId());
        var row=jdbc.queryForMap("SELECT id,content FROM xl_feedback WHERE user_id=? AND client_request_id=?",user,input.clientRequestId());
        if(!content.equals(row.get("content"))) throw new BusinessException(ErrorCode.DUPLICATE_REQUEST_CONFLICT);
        return ApiResponse.success(Map.of("id",String.valueOf(row.get("id"))));
    }
    @GetMapping("/api/v1/admin/feedback")
    public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int pageSize, @AuthenticationPrincipal AuthPrincipal actor) {
        support.requireAdmin(actor);
        if(page<1 || pageSize<1 || pageSize>50 || page>100000) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var items=jdbc.queryForList("SELECT f.id,f.user_id AS userId,u.nickname,f.content,f.created_at AS createdAt FROM xl_feedback f LEFT JOIN xl_user u ON u.id=f.user_id ORDER BY f.created_at DESC,f.id DESC LIMIT ? OFFSET ?",pageSize,(long)(page-1)*pageSize);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM xl_feedback",Long.class);
        return ApiResponse.success(Map.of("items",items,"total",total,"page",page,"pageSize",pageSize));
    }
}
