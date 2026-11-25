package cn.xinkelink.statistics;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.forum.ForumService;

import java.util.*;

import static cn.xinkelink.content.ContentSupport.map;

/** Public Top5 is re-authorized and projected through the existing forum query rules on every read. */
public class ViewRankingService {
    private static final int BATCH_SIZE = 50, LIMIT = 5;
    private final ViewProjection projection;
    private final ForumService forum;

    public ViewRankingService(ViewProjection projection, ForumService forum) {
        this.projection = projection; this.forum = forum;
    }

    public Map<String, Object> read(AuthPrincipal viewer) {
        ViewProjection.Snapshot snapshot = projection.read();
        List<Map<String, Object>> items = new ArrayList<>();
        var ranked = snapshot.entries();
        for (int start = 0; start < ranked.size() && items.size() < LIMIT; start += BATCH_SIZE) {
            var candidates = ranked.subList(start, Math.min(ranked.size(), start + BATCH_SIZE));
            // favoriteItems is the existing *public* batch projection; it never makes hidden owner
            // details visible, and retains anonymous author and signed-image access behavior.
            var visible = forum.favoriteItems(candidates.stream().map(ViewProjection.Entry::postId).toList(), viewer);
            for (var entry : candidates) {
                var post = visible.get(entry.postId());
                if (post != null) items.add(map("post", post, "pv", entry.pv(), "uv", entry.uv()));
                if (items.size() == LIMIT) break;
            }
        }
        return map("items", items, "window", map("startDate", snapshot.startDate(), "endDate", snapshot.endDate(),
                "zone", ViewProjection.ZONE.getId(), "days", 7), "updatedAt", snapshot.updatedAt(), "available", snapshot.available());
    }
}
