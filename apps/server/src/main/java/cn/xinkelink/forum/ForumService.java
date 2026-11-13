package cn.xinkelink.forum;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.CursorPage;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentCursor;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.media.MediaService;
import cn.xinkelink.notification.NotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.xinkelink.content.ContentSupport.map;
import static cn.xinkelink.forum.ForumModels.*;

@Service
@Profile("auth")
@Transactional(readOnly = true)
public class ForumService {
    private final ForumMapper mapper;
    private final ContentSupport support;
    private final ContentCursor cursors;
    private final MediaService media;
    private final NotificationService notifications;

    public ForumService(ForumMapper mapper, ContentSupport support, ContentCursor cursors, MediaService media, NotificationService notifications) {
        this.mapper = mapper; this.support = support; this.cursors = cursors; this.media = media; this.notifications=notifications;
    }

    public CursorPage<Map<String, Object>> list(String category, String sort, String keyword,
            String cursor, Integer size, AuthPrincipal principal) {
        if (category != null && !Set.of("campus", "lost_found", "study", "help").contains(category)) invalid();
        String order = sort == null ? "recommended" : sort;
        if (!Set.of("recommended", "latest").contains(order)) invalid();
        String query = ContentSupport.keyword(keyword);
        Long viewer = support.viewer(principal);
        String scope = "forum:" + category + ":" + order + ":" + query;
        ContentCursor.Position position = cursors.read(cursor, scope, viewer);
        int limit = ContentSupport.size(size);
        List<PostRow> rows = mapper.posts(null, category, likeKeyword(query), position.snapshot(), position.at(), position.id(), limit + 1);
        return postPage(rows, limit, scope, viewer, position, principal, false);
    }

    public CursorPage<Map<String, Object>> mine(String cursor, Integer size, AuthPrincipal principal) {
        long owner = support.require(principal, null);
        String scope = "forum:mine";
        var position = cursors.read(cursor, scope, owner);
        int limit = ContentSupport.size(size);
        var rows = mapper.posts(owner, null, null, position.snapshot(), position.at(), position.id(), limit + 1);
        return postPage(rows, limit, scope, owner, position, principal, true);
    }

    public Map<String, Object> detail(long id, AuthPrincipal principal) {
        Long viewer = support.viewer(principal);
        PostRow post = mapper.post(id);
        if (post == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        boolean mine = Objects.equals(viewer, post.authorId());
        if (!isPublic(post) && !mine) unavailable();
        return postViews(List.of(post), principal, true, mine).getFirst();
    }

    public Map<Long, Map<String, Object>> favoriteItems(Collection<Long> ids, AuthPrincipal principal) {
        if (ids == null || ids.isEmpty()) return Map.of();
        var rows = mapper.publicPosts(ids);
        var views = postViews(rows, principal, false, false);
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) result.put(rows.get(i).id(), views.get(i));
        return result;
    }

    @Transactional
    public Map<String, Object> create(CreatePost input, AuthPrincipal principal) {
        long owner = support.require(principal, "publish");
        validate(input);
        CreatePost normalized = new CreatePost(input.content().strip(), input.category(), List.copyOf(input.imageIds()), input.anonymous(), input.clientRequestId());
        byte[] hash = ContentSupport.hash(normalized);
        // 同一作者的请求标识在事务内串行检查，重试不重新绑定图片。
        mapper.lockUser(owner);
        PostRow previous = mapper.requestedPost(owner, input.clientRequestId());
        if (previous != null) { sameHash(previous.requestHash(), hash); return detail(previous.id(), principal); }
        String label = input.anonymous() ? "匿名同学" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : null;
        mapper.insertPost(owner, normalized, label, hash);
        PostRow created = mapper.requestedPost(owner, input.clientRequestId());
        media.bind(owner, "post", input.imageIds(), "post", created.id());
        return detail(created.id(), principal);
    }

    @Transactional
    public Map<String, Object> update(long id, UpdatePost input, AuthPrincipal principal) {
        long owner = support.require(principal, "publish");
        validate(input);
        PostRow post = requirePost(id, true);
        if (post.authorId() != owner) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (post.deletedAt() != null) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        ContentSupport.version(post.version(), input.expectedVersion());
        UpdatePost normalized = new UpdatePost(input.content() == null ? null : input.content().strip(),
                input.category(), input.imageIds(), input.expectedVersion());
        if (input.imageIds() != null) media.bind(owner, "post", input.imageIds(), "post", id);
        // 仅修改可编辑正文；匿名、归属、初次摘要和治理标记始终保留。
        if (mapper.updatePost(id, normalized) != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        return detail(id, principal);
    }

    @Transactional
    public Map<String, Object> deletePost(long id, long version, AuthPrincipal principal) {
        long owner = support.require(principal, null);
        PostRow post = requirePost(id, true);
        if (post.authorId() != owner) throw new BusinessException(ErrorCode.FORBIDDEN);
        ContentSupport.version(post.version(), version);
        if (post.deletedAt() != null) return map("deleted", true, "removedCommentCount", 0, "version", post.version());
        int removed = mapper.deletePostComments(id);
        mapper.deletePost(id);
        media.detach("post", id);
        return map("deleted", true, "removedCommentCount", removed, "version", post.version() + 1);
    }

    @Transactional
    public Map<String, Object> likePost(long id, boolean add, AuthPrincipal principal) {
        notifications.prepareReaction("post",id,principal);
        long viewer = support.require(principal, null);
        PostRow post = mapper.lockPost(id);
        if (add && !isPublic(post)) unavailable();
        int changed = add ? mapper.addLike(viewer, "post", id) : mapper.removeLike(viewer, "post", id);
        if(changed>0)notifications.reaction("post",id,viewer,"post_like",add);
        return map("liked", add, "likeCount", mapper.likes("post", id), "changed", changed > 0);
    }

    @Transactional
    public Map<String, Object> favorite(long id, boolean add, AuthPrincipal principal) {
        notifications.prepareReaction("post",id,principal);
        long viewer = support.require(principal, null);
        PostRow post = mapper.lockPost(id);
        if (add && !isPublic(post)) unavailable();
        int changed = add ? mapper.addFavorite(viewer, id) : mapper.removeFavorite(viewer, id);
        if(changed>0)notifications.reaction("post",id,viewer,"post_favorite",add);
        return map("favorited", add, "changed", changed > 0);
    }

    public CursorPage<Map<String, Object>> comments(long id, String cursor, Integer size, AuthPrincipal principal) {
        PostRow post = requirePublicPost(id, false);
        Long viewer = support.viewer(principal);
        String scope = "forum:comments:" + id;
        var position = cursors.read(cursor, scope, viewer);
        int limit = ContentSupport.size(size);
        List<CommentRow> rows = mapper.comments(id, null, position.snapshot(), position.at(), position.id(), limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        if (rows.isEmpty()) return new CursorPage<>(List.of(), null, false);
        List<CommentRow> previews = mapper.previewReplies(rows.stream().map(CommentRow::id).toList(), position.snapshot());
        List<CommentRow> all = new ArrayList<>(rows); all.addAll(previews);
        Map<Long, Map<String, Object>> views = commentViews(all, post, viewer);
        Map<Long, List<Map<String, Object>>> replyViews = previews.stream().collect(Collectors.groupingBy(CommentRow::rootId,
                Collectors.mapping(row -> views.get(row.id()), Collectors.toList())));
        Map<Long, CommentMetric> metrics = metrics(all, viewer);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommentRow row : rows) {
            Map<String, Object> view = views.get(row.id());
            view.put("replyCount", metrics.get(row.id()).replyCount());
            view.put("replies", replyViews.getOrDefault(row.id(), List.of()));
            result.add(view);
        }
        CommentRow last = rows.getLast();
        return new CursorPage<>(result, more ? cursors.next(scope, viewer,
                new ContentCursor.Position(position.snapshot(), last.createdAt(), last.id(), null)) : null, more);
    }

    public CursorPage<Map<String, Object>> replies(long rootId, String cursor, Integer size, AuthPrincipal principal) {
        CommentRow root = mapper.comment(rootId);
        if (root == null || root.rootId() != null || !"post".equals(root.targetType())) unavailable();
        PostRow post = requirePublicPost(root.targetId(), false);
        if (!visible(root)) unavailable();
        Long viewer = support.viewer(principal);
        String scope = "forum:replies:" + rootId;
        var position = cursors.read(cursor, scope, viewer);
        int limit = ContentSupport.size(size);
        List<CommentRow> rows = mapper.comments(post.id(), rootId, position.snapshot(), position.at(), position.id(), limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        var views = commentViews(rows, post, viewer);
        String next = null;
        if (more) {
            var last = rows.getLast();
            next = cursors.next(scope, viewer, new ContentCursor.Position(position.snapshot(), last.createdAt(), last.id(), null));
        }
        return new CursorPage<>(rows.stream().map(row -> views.get(row.id())).toList(), next, more);
    }

    @Transactional
    public Map<String, Object> createComment(long id, CreateComment input, AuthPrincipal principal) {
        validate(input);
        Long replyId = input.replyToCommentId() == null ? null : ContentSupport.id(input.replyToCommentId());
        notifications.prepareComment(id,replyId,principal);
        long owner = support.require(principal, "comment");
        CreateComment normalized = new CreateComment(input.content().strip(), replyId == null ? null : replyId.toString(), input.clientRequestId());
        byte[] hash = ContentSupport.hash(map("postId", Long.toString(id), "input", normalized));
        mapper.lockUser(owner);
        CommentRow previous = mapper.requestedComment(owner, input.clientRequestId());
        if (previous != null) {
            sameHash(previous.requestHash(), hash);
            PostRow post = requirePost(id, true);
            return commentResult(previous, post, owner);
        }
        PostRow post = requirePublicPost(id, true);
        Long rootId = null;
        if (replyId != null) {
            CommentRow target = mapper.comment(replyId);
            if (!visible(target) || !"post".equals(target.targetType()) || target.targetId() != id) invalidReply();
            rootId = target.rootId() == null ? target.id() : target.rootId();
            CommentRow root = mapper.comment(rootId);
            if (!visible(root) || root.rootId() != null || !"post".equals(root.targetType()) || root.targetId() != id) invalidReply();
        }
        mapper.insertComment(id, owner, rootId, replyId, normalized, hash);
        notifications.comment(mapper.requestedComment(owner,input.clientRequestId()).id());
        return commentResult(mapper.requestedComment(owner, input.clientRequestId()), post, owner);
    }

    @Transactional
    public Map<String, Object> deleteComment(long id, long version, AuthPrincipal principal) {
        long owner = support.require(principal, null);
        CommentRow comment = mapper.comment(id);
        if (comment == null || !"post".equals(comment.targetType())) throw new BusinessException(ErrorCode.NOT_FOUND);
        requirePost(comment.targetId(), true);
        comment = mapper.comment(id);
        if (comment.authorId() != owner) throw new BusinessException(ErrorCode.FORBIDDEN);
        ContentSupport.version(comment.version(), version);
        int removed = comment.deletedAt() == null ? mapper.deleteComment(id) : 0;
        return map("deleted", true, "removedCount", removed, "postCommentCount", mapper.commentCount(comment.targetId()),
                "version", comment.version() + (removed > 0 ? 1 : 0));
    }

    @Transactional
    public Map<String, Object> likeComment(long id, boolean add, AuthPrincipal principal) {
        notifications.prepareReaction("comment",id,principal);
        long viewer = support.require(principal, null);
        if (add) {
            CommentRow comment = mapper.comment(id);
            if (comment == null || !"post".equals(comment.targetType())) unavailable();
            requirePublicPost(comment.targetId(), true);
            comment = mapper.comment(id);
            if (!visible(comment)) unavailable();
            if (comment.rootId() != null && !visible(mapper.comment(comment.rootId()))) unavailable();
        }
        int changed = add ? mapper.addLike(viewer, "comment", id) : mapper.removeLike(viewer, "comment", id);
        if(changed>0)notifications.reaction("comment",id,viewer,"comment_like",add);
        return map("liked", add, "likeCount", mapper.likes("comment", id), "changed", changed > 0);
    }

    private Map<String, Object> commentResult(CommentRow row, PostRow post, Long viewer) {
        // 已删评论的重试保留资源身份和当前版本，不重新暴露已删正文。
        Map<String, Object> view = commentViews(List.of(row), post, viewer).get(row.id());
        if (row.deletedAt() != null) view.put("content", null);
        return map("comment", view, "commentCount", isPublic(post) ? mapper.commentCount(post.id()) : 0L);
    }

    private CursorPage<Map<String, Object>> postPage(List<PostRow> rows, int limit, String scope, Long viewer,
            ContentCursor.Position position, AuthPrincipal principal, boolean owner) {
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        String next = null;
        if (more) {
            var last = rows.getLast();
            next = cursors.next(scope, viewer, new ContentCursor.Position(position.snapshot(), last.createdAt(), last.id(), null));
        }
        return new CursorPage<>(postViews(rows, principal, owner, owner), next, more);
    }

    private List<Map<String, Object>> postViews(List<PostRow> rows, AuthPrincipal principal, boolean detail, boolean ownerDetail) {
        if (rows.isEmpty()) return List.of();
        Long viewer = support.viewer(principal);
        List<Long> ids = rows.stream().map(PostRow::id).toList();
        var images = media.images("post", ids, support.viewer(principal)==null?null:principal);
        var users = support.users(rows.stream().filter(row -> !row.anonymous()).map(PostRow::authorId).distinct().toList());
        var metrics = mapper.postMetrics(ids, viewer).stream().collect(Collectors.toMap(PostMetric::id, Function.identity()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PostRow row : rows) {
            var metric = metrics.get(row.id());
            boolean mine = Objects.equals(viewer, row.authorId());
            var view = map("id", Long.toString(row.id()), "category", row.category(), "content", row.deletedAt() == null ? row.content() : null,
                    "images", images.getOrDefault(row.id(), List.of()), "author", author(row.authorId(), row, users),
                    "createdAt", row.createdAt(), "likeCount", metric.likeCount(), "commentCount", isPublic(row) ? metric.commentCount() : 0L,
                    "liked", metric.liked(), "favorited", metric.favorited(), "isMine", mine, "version", row.version());
            if (detail) view.put("permissions", map("canDelete", mine && row.deletedAt() == null, "canComment", isPublic(row)));
            if (ownerDetail && mine) {
                view.put("availability", row.deletedAt() != null ? "deleted" : "hidden".equals(row.status()) ? "hidden" : "pending".equals(row.reviewState()) ? "pending" : "available");
                view.put("status", row.status()); view.put("reviewState", row.reviewState()); view.put("moderationReason", null);
            }
            result.add(view);
        }
        return result;
    }

    private Map<Long, Map<String, Object>> commentViews(List<CommentRow> rows, PostRow post, Long viewer) {
        if (rows.isEmpty()) return Map.of();
        Set<Long> replyIds = rows.stream().map(CommentRow::replyToId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, CommentRow> references = replyIds.isEmpty() ? Map.of() : mapper.commentsByIds(replyIds).stream().collect(Collectors.toMap(CommentRow::id, Function.identity()));
        Set<Long> userIds = rows.stream().map(CommentRow::authorId).collect(Collectors.toSet());
        references.values().stream().filter(ForumService::visible).map(CommentRow::authorId).forEach(userIds::add);
        if (post.anonymous()) userIds.remove(post.authorId());
        var users = support.users(userIds);
        var metrics = metrics(rows, viewer);
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (CommentRow row : rows) {
            var metric = metrics.get(row.id());
            CommentRow reply = row.replyToId() == null ? null : references.get(row.replyToId());
            String name = visible(reply) ? author(reply.authorId(), post, users).get("nickname").toString() : "";
            var view = map("id", Long.toString(row.id()), "targetType", row.targetType(), "targetId", Long.toString(row.targetId()),
                    "rootId", row.rootId() == null ? null : row.rootId().toString(), "replyToId", row.replyToId() == null ? null : row.replyToId().toString(),
                    "author", author(row.authorId(), post, users), "replyTo", row.replyToId() == null ? null : map("commentId", row.replyToId().toString(), "name", name),
                    "content", row.deletedAt() == null ? row.content() : null, "createdAt", row.createdAt(), "likeCount", metric.likeCount(), "liked", metric.liked(),
                    "isPostAuthor", row.authorId() == post.authorId(), "isMine", Objects.equals(viewer, row.authorId()), "version", row.version());
            result.put(row.id(), view);
        }
        return result;
    }

    private Map<Long, CommentMetric> metrics(List<CommentRow> rows, Long viewer) {
        return mapper.commentMetrics(rows.stream().map(CommentRow::id).toList(), viewer).stream().collect(Collectors.toMap(CommentMetric::id, Function.identity()));
    }

    private Map<String, Object> author(long user, PostRow post, Map<Long, Map<String, Object>> users) {
        if (post.anonymous() && user == post.authorId()) return map("id", null, "nickname", post.anonymousLabel(), "avatar", null, "isAnonymous", true);
        var profile = users.getOrDefault(user, map("nickname", "用户", "avatar", null));
        return map("id", Long.toString(user), "nickname", profile.get("nickname"), "avatar", profile.get("avatar"), "isAnonymous", false);
    }

    private PostRow requirePost(long id, boolean lock) {
        PostRow row = lock ? mapper.lockPost(id) : mapper.post(id);
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return row;
    }

    private PostRow requirePublicPost(long id, boolean lock) {
        PostRow row = requirePost(id, lock);
        if (!isPublic(row)) unavailable();
        return row;
    }

    private static boolean isPublic(PostRow row) { return row != null && row.deletedAt() == null && "visible".equals(row.status()) && "normal".equals(row.reviewState()); }
    private static boolean visible(CommentRow row) { return row != null && row.deletedAt() == null && "visible".equals(row.status()); }
    private static String likeKeyword(String value) { return value == null || value.isEmpty() ? null : "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    private static void sameHash(byte[] previous, byte[] hash) { if (!java.security.MessageDigest.isEqual(previous, hash)) throw new BusinessException(ErrorCode.DUPLICATE_REQUEST_CONFLICT); }
    private static void invalid() { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
    private static void unavailable() { throw new BusinessException(ErrorCode.TARGET_UNAVAILABLE); }
    private static void invalidReply() { throw new BusinessException(ErrorCode.REPLY_TARGET_INVALID); }
    private static void requestId(String value) { if (value == null || !value.matches("[\\x21-\\x7E]{1,64}")) invalid(); }
    private static void validate(CreatePost input) {
        if (input == null || input.content() == null || input.content().isBlank() || input.content().length() > 2000
                || input.category() == null || !Set.of("campus", "lost_found", "study", "help").contains(input.category())
                || input.imageIds() == null || input.imageIds().size() > 9 || input.anonymous() == null) invalid();
        requestId(input.clientRequestId());
        if (new HashSet<>(input.imageIds()).size() != input.imageIds().size()) invalid();
        input.imageIds().forEach(ContentSupport::id);
    }
    private static void validate(CreateComment input) {
        if (input == null || input.content() == null || input.content().isBlank() || input.content().length() > 1000) invalid();
        requestId(input.clientRequestId());
    }
    private static void validate(UpdatePost input) {
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 0
                || input.content() == null && input.category() == null && input.imageIds() == null) invalid();
        if (input.content() != null && (input.content().isBlank() || input.content().length() > 2000)) invalid();
        if (input.category() != null && !Set.of("campus", "lost_found", "study", "help").contains(input.category())) invalid();
        if (input.imageIds() != null) {
            if (input.imageIds().size() > 9 || new HashSet<>(input.imageIds()).size() != input.imageIds().size()) invalid();
            input.imageIds().forEach(ContentSupport::id);
        }
    }
}
