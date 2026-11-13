package cn.xinkelink.content;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.CursorPage;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile("auth")
@RequestMapping("/api/v1")
public class DiscoveryController {
    private final DiscoveryService service;
    public DiscoveryController(DiscoveryService service){this.service=service;}
    @GetMapping("/search")
    ApiResponse<Map<String,Object>> search(@RequestParam String q,@RequestParam String type,
            @RequestParam(required=false) String category,@RequestParam(required=false) String sort,
            @RequestParam(required=false) String filter,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer size,@AuthenticationPrincipal AuthPrincipal viewer){
        return ApiResponse.success(service.search(q,type,category,sort,filter,cursor,size,viewer));
    }
    @GetMapping("/me/favorites")
    ApiResponse<CursorPage<Map<String,Object>>> favorites(@RequestParam String type,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer size,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.favorites(type,cursor,size,viewer));}
    @GetMapping("/me/summary")
    ApiResponse<Map<String,Object>> summary(@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.summary(viewer));}
    @GetMapping("/me/likes")
    ApiResponse<CursorPage<Map<String,Object>>> likes(@RequestParam String type,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer size,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.likes(type,cursor,size,viewer));}
    @GetMapping("/users/{userId}")
    ApiResponse<Map<String,Object>> profile(@PathVariable String userId){return ApiResponse.success(service.profile(ContentSupport.id(userId)));}
    @GetMapping("/users/{userId}/posts")
    ApiResponse<CursorPage<Map<String,Object>>> posts(@PathVariable String userId,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer size,@AuthenticationPrincipal AuthPrincipal viewer){return ApiResponse.success(service.authorPosts(ContentSupport.id(userId),cursor,size,viewer));}
}
