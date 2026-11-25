package cn.xinkelink.forum;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ForumModels {
    private ForumModels() {}

    public record CreatePost(@NotBlank @Size(max = 2000) String content,
            @NotNull @Pattern(regexp = "campus|lost_found|study|help|secondhand|complain|buddy") String category,
            @NotNull @Size(max = 9) List<@NotBlank String> imageIds,
            @NotNull Boolean anonymous,
            @NotNull @Pattern(regexp = "[\\x21-\\x7E]{1,64}") String clientRequestId) {}

    public record CreateComment(@NotBlank @Size(max = 1000) String content, String replyToCommentId,
            @NotNull @Pattern(regexp = "[\\x21-\\x7E]{1,64}") String clientRequestId) {}

    public record UpdatePost(@Size(min = 1, max = 2000) String content,
            @Pattern(regexp = "campus|lost_found|study|help|secondhand|complain|buddy") String category,
            @Size(max = 9) List<@NotBlank String> imageIds,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record PostRow(long id, long authorId, String category, String content, boolean anonymous,
            String anonymousLabel, String status, String reviewState, byte[] requestHash,
            Instant createdAt, long version, Instant deletedAt) {}

    public record CommentRow(long id, String targetType, long targetId, long authorId, Long rootId,
            Long replyToId, String content, String status, byte[] requestHash, Instant createdAt,
            long version, Instant deletedAt) {}

    public record PublicPostMetric(long id, long likeCount, long commentCount) {}
    public record PostViewerState(long id, boolean liked, boolean favorited) {}
    public record CommentMetric(long id, long likeCount, long replyCount, boolean liked) {}
}
