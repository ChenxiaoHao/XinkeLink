package cn.xinkelink.statistics;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("auth")
public class ViewRankingController {
    private final ViewRankingService ranking;
    public ViewRankingController(ViewRankingService ranking) { this.ranking = ranking; }

    @GetMapping("/api/v1/posts/view-ranking")
    public ApiResponse<Map<String, Object>> ranking(@AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(ranking.read(viewer));
    }
}
