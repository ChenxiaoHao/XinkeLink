package cn.xinkelink.forum;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.CursorPage;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.statistics.ViewStatisticsProperties;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.xinkelink.forum.ForumModels.*;

@RestController
@Profile("auth")
public class ForumController {
    private final ForumService service;
    private final ViewStatisticsProperties statistics;
    public ForumController(ForumService service, ViewStatisticsProperties statistics) {
        this.service = service;
        this.statistics = statistics;
    }

    @GetMapping("/api/v1/posts")
    ApiResponse<CursorPage<Map<String, Object>>> list(@RequestParam(required = false) String category,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.list(category, sort, keyword, cursor, size, viewer));
    }

    @PostMapping("/api/v1/posts")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreatePost input, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.create(input, viewer));
    }

    @GetMapping("/api/v1/posts/{postId}")
    ApiResponse<Map<String, Object>> detail(@PathVariable String postId, @AuthenticationPrincipal AuthPrincipal viewer) {
        var detail = new LinkedHashMap<>(service.detail(ContentSupport.id(postId), viewer));
        detail.put("viewReportingEnabled", statistics.isEnabled());
        return ApiResponse.success(detail);
    }

    @PatchMapping("/api/v1/posts/{postId}")
    ApiResponse<Map<String, Object>> update(@PathVariable String postId, @Valid @RequestBody UpdatePost input,
            @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.update(ContentSupport.id(postId), input, viewer));
    }

    @DeleteMapping("/api/v1/posts/{postId}")
    ApiResponse<Map<String, Object>> deletePost(@PathVariable String postId,
            @RequestHeader(value = "If-Match", required = false) String version, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.deletePost(ContentSupport.id(postId), ContentSupport.ifMatch(version), viewer));
    }

    @GetMapping("/api/v1/me/posts")
    ApiResponse<CursorPage<Map<String, Object>>> mine(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.mine(cursor, size, viewer));
    }

    @PutMapping("/api/v1/posts/{postId}/like")
    ApiResponse<Map<String, Object>> addLike(@PathVariable String postId, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.likePost(ContentSupport.id(postId), true, viewer));
    }

    @DeleteMapping("/api/v1/posts/{postId}/like")
    ApiResponse<Map<String, Object>> removeLike(@PathVariable String postId, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.likePost(ContentSupport.id(postId), false, viewer));
    }

    @PutMapping("/api/v1/posts/{postId}/favorite")
    ApiResponse<Map<String, Object>> addFavorite(@PathVariable String postId, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.favorite(ContentSupport.id(postId), true, viewer));
    }

    @DeleteMapping("/api/v1/posts/{postId}/favorite")
    ApiResponse<Map<String, Object>> removeFavorite(@PathVariable String postId, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.favorite(ContentSupport.id(postId), false, viewer));
    }

    @GetMapping("/api/v1/posts/{postId}/comments")
    ApiResponse<CursorPage<Map<String, Object>>> comments(@PathVariable String postId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.comments(ContentSupport.id(postId), cursor, size, viewer));
    }

    @PostMapping("/api/v1/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Map<String, Object>> comment(@PathVariable String postId, @Valid @RequestBody CreateComment input,
            @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.createComment(ContentSupport.id(postId), input, viewer));
    }

    @GetMapping("/api/v1/comments/{commentId}/replies")
    ApiResponse<CursorPage<Map<String, Object>>> replies(@PathVariable String commentId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.replies(ContentSupport.id(commentId), cursor, size, viewer));
    }

    @DeleteMapping("/api/v1/comments/{commentId}")
    ApiResponse<Map<String, Object>> deleteComment(@PathVariable String commentId,
            @RequestHeader(value = "If-Match", required = false) String version, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.deleteComment(ContentSupport.id(commentId), ContentSupport.ifMatch(version), viewer));
    }

    @PutMapping("/api/v1/comments/{commentId}/like")
    ApiResponse<Map<String, Object>> likeComment(@PathVariable String commentId, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.likeComment(ContentSupport.id(commentId), true, viewer));
    }

    @DeleteMapping("/api/v1/comments/{commentId}/like")
    ApiResponse<Map<String, Object>> unlikeComment(@PathVariable String commentId, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.likeComment(ContentSupport.id(commentId), false, viewer));
    }
}
