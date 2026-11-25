package cn.xinkelink.forum;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "auth"})
@Transactional
class ForumServiceIT {
    @Autowired ForumService forum;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    AuthPrincipal author;
    AuthPrincipal reader;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @BeforeEach
    void users() {
        author = principal("匿名真实作者");
        reader = principal("评论同学");
    }

    @Test
    void createsEditsAndFiltersAllForumCategories() {
        for(String category:List.of("campus","lost_found","study","help","secondhand","complain","buddy")) {
            var post=forum.create(new ForumModels.CreatePost("category regression",category,List.of(),false,UUID.randomUUID().toString()),author);
            long id=Long.parseLong(post.get("id").toString());
            assertEquals(category,forum.detail(id,author).get("category"));
            assertTrue(forum.list(category,"latest",null,null,50,author).items().stream().anyMatch(row->row.get("id").equals(post.get("id"))));
            forum.update(id,new ForumModels.UpdatePost(null,"campus",null,0L),author);
            assertEquals(category,forum.update(id,new ForumModels.UpdatePost(null,category,null,1L),author).get("category"));
        }
    }

    @Test
    void anonymousPostAndAuthorRepliesNeverExposeTheRealIdentity() {
        long post = create(true);
        Map<String, Object> root = comment(post, reader, null, "一级");
        Map<String, Object> reply = comment(post, author, root.get("id").toString(), "匿名回复");
        Map<String, Object> nested = comment(post, reader, reply.get("id").toString(), "回复二级仍归同根");
        assertEquals(root.get("id"), nested.get("rootId"));
        Map<?, ?> postAuthor = (Map<?, ?>) forum.detail(post, null).get("author");
        assertNull(postAuthor.get("id"));
        assertEquals(postAuthor, reply.get("author"));
        assertTrue((boolean) reply.get("isPostAuthor"));
        assertFalse(reply.toString().contains("匿名真实作者"));
        var threads = forum.comments(post, null, 20, null);
        assertEquals(1, threads.items().size());
        assertEquals(2L, threads.items().getFirst().get("replyCount"));
        assertEquals(1, ((List<?>) threads.items().getFirst().get("replies")).size());
        assertEquals(2, forum.replies(Long.parseLong(root.get("id").toString()), null, 20, null).items().size());
        assertEquals(3L, forum.detail(post, null).get("commentCount"));
    }

    @Test
    void originalPayloadHashControlsCreateRetriesAndAllRelationshipsAreIdempotent() {
        String key = UUID.randomUUID().toString();
        var input = new ForumModels.CreatePost("原文", "study", List.of(), false, key);
        Map<String, Object> first = forum.create(input, author);
        assertEquals(first.get("id"), forum.create(input, author).get("id"));
        BusinessException conflict = assertThrows(BusinessException.class, () -> forum.create(
                new ForumModels.CreatePost("改文", "study", List.of(), false, key), author));
        assertEquals(ErrorCode.DUPLICATE_REQUEST_CONFLICT, conflict.code());
        long post = Long.parseLong(first.get("id").toString());
        assertEquals(true, forum.likePost(post, true, reader).get("changed"));
        assertEquals(false, forum.likePost(post, true, reader).get("changed"));
        assertEquals(1L, forum.detail(post, reader).get("likeCount"));
        assertEquals(true, forum.favorite(post, true, reader).get("changed"));
        assertEquals(false, forum.favorite(post, true, reader).get("changed"));
        assertEquals(true, forum.favorite(post, false, reader).get("changed"));
        assertEquals(false, forum.favorite(post, false, reader).get("changed"));
    }

    @Test
    void rootDeletionRemovesOnlyItsRepliesAndVersionConflictsLeaveEverythingIntact() {
        long post = create(false);
        var root = comment(post, reader, null, "根");
        long rootId = Long.parseLong(root.get("id").toString());
        comment(post, author, root.get("id").toString(), "回复");
        comment(post, author, null, "其他根");
        assertThrows(BusinessException.class, () -> forum.deleteComment(rootId, 7, reader));
        assertEquals(3L, forum.detail(post, null).get("commentCount"));
        assertEquals(2, forum.deleteComment(rootId, 0, reader).get("removedCount"));
        assertEquals(1L, forum.detail(post, null).get("commentCount"));
        assertThrows(BusinessException.class, () -> forum.replies(rootId, null, 20, null));
        assertEquals(1, forum.deletePost(post, 0, author).get("removedCommentCount"));
        assertThrows(BusinessException.class, () -> forum.detail(post, null));
        assertEquals(false, forum.likePost(post, false, reader).get("liked"));
    }

    @Test
    void publicListsCountsAndFavoritesShareParentAndRootVisibility() {
        long post = create(false);
        var root = comment(post, author, null, "根");
        comment(post, reader, root.get("id").toString(), "回复");
        jdbc.update("UPDATE xl_comment SET status='hidden' WHERE id=?", root.get("id"));
        assertEquals(0L, forum.detail(post, null).get("commentCount"));
        assertTrue(forum.comments(post, null, 20, null).items().isEmpty());
        jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?", post);
        assertTrue(forum.favoriteItems(List.of(post), reader).isEmpty());
        assertThrows(BusinessException.class, () -> forum.detail(post, reader));
        assertEquals("hidden", forum.detail(post, author).get("availability"));
        assertTrue(forum.mine(null, 20, author).items().stream().anyMatch(item -> item.get("id").equals(Long.toString(post))));
    }

    @Test
    void replyCannotCrossPostsAndCommentRequestIdsCannotChangeTargets() {
        long post = create(false);
        long other = create(false);
        var root = comment(post, author, null, "根");
        BusinessException invalid = assertThrows(BusinessException.class, () -> comment(other, reader, root.get("id").toString(), "越界"));
        assertEquals(ErrorCode.REPLY_TARGET_INVALID, invalid.code());
        String key = UUID.randomUUID().toString();
        var input = new ForumModels.CreateComment("原文", null, key);
        var first = forum.createComment(post, input, reader);
        assertEquals(first.get("comment"), forum.createComment(post, input, reader).get("comment"));
        assertEquals(ErrorCode.DUPLICATE_REQUEST_CONFLICT, assertThrows(BusinessException.class,
                () -> forum.createComment(other, input, reader)).code());
    }

    @Test
    void keysetPaginationBindsFiltersAndViewerAndDoesNotDuplicateEqualTimestamps() {
        create(false); create(false); create(false);
        var first = forum.list("campus", "latest", null, null, 1, author);
        assertTrue(first.hasMore());
        var second = forum.list("campus", "latest", null, first.nextCursor(), 1, author);
        assertNotEquals(first.items().getFirst().get("id"), second.items().getFirst().get("id"));
        assertEquals(ErrorCode.CURSOR_INVALID, assertThrows(BusinessException.class,
                () -> forum.list("study", "latest", null, first.nextCursor(), 1, author)).code());
        assertEquals(ErrorCode.CURSOR_INVALID, assertThrows(BusinessException.class,
                () -> forum.list("campus", "latest", null, first.nextCursor(), 1, reader)).code());
    }

    @Test
    void nicknameSearchAndEscapedLikeNeverRevealAnonymousAuthorsOrTreatWildcardsAsOperators() {
        long anonymous = create(true);
        assertFalse(forum.list(null, "latest", "匿名真实作者", null, 50, null).items().stream()
                .anyMatch(item -> item.get("id").equals(Long.toString(anonymous))));
        long publicPost = create(false);
        assertTrue(forum.list(null, "latest", "匿名真实作者", null, 50, null).items().stream()
                .anyMatch(item -> item.get("id").equals(Long.toString(publicPost))));
        assertTrue(forum.list(null, "latest", "%_", null, 50, null).items().isEmpty());
    }

    @Test
    void commentLikesAreIdempotentAndDeletedReplyNamesBecomeEmpty() {
        long post = create(true);
        var root = comment(post, reader, null, "根");
        var reply = comment(post, author, root.get("id").toString(), "匿名回复");
        var next = comment(post, reader, reply.get("id").toString(), "继续回复");
        long id = Long.parseLong(reply.get("id").toString());
        assertEquals(true, forum.likeComment(id, true, reader).get("changed"));
        assertEquals(false, forum.likeComment(id, true, reader).get("changed"));
        assertEquals(1L, forum.likeComment(id, true, reader).get("likeCount"));
        forum.deleteComment(id, 0, author);
        var remaining = forum.replies(Long.parseLong(root.get("id").toString()), null, 20, null);
        assertEquals(next.get("id"), remaining.items().getFirst().get("id"));
        assertEquals("", ((Map<?, ?>) remaining.items().getFirst().get("replyTo")).get("name"));
        assertEquals(true, forum.likeComment(id, false, reader).get("changed"));
        assertEquals(false, forum.likeComment(id, false, reader).get("changed"));
    }

    @Test
    void httpRequiresIfMatchAndRejectsClientOwnedFieldsWhilePublicReadsWork() throws Exception {
        long id = create(true);
        var identity = new UsernamePasswordAuthenticationToken(author, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(get("/api/v1/posts/{id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.author.id").doesNotExist());
        mvc.perform(delete("/api/v1/posts/{id}", id).with(authentication(identity)).with(csrf()))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(delete("/api/v1/posts/{id}", id).header("If-Match", "\"9\"").with(authentication(identity)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(post("/api/v1/posts").with(authentication(identity)).with(csrf())
                        .contentType("application/json").content("{\"content\":\"正文\",\"category\":\"campus\",\"imageIds\":[],\"anonymous\":true,\"clientRequestId\":\"http-test\",\"authorId\":\"123\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/posts/{id}", id).header("If-Match", "\"0\"").with(authentication(identity)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void editingAnonymousPostPreservesIdentityGovernanceCreationTimeAndOriginalRequestHash() throws Exception {
        var input = new ForumModels.CreatePost("原始正文", "campus", List.of(), true, UUID.randomUUID().toString());
        var initial = forum.create(input, author);
        long id = Long.parseLong(initial.get("id").toString());
        var original = jdbc.queryForMap("SELECT author_id,is_anonymous,anonymous_label,created_at,request_hash FROM xl_post WHERE id=?", id);
        jdbc.update("UPDATE xl_post SET status='hidden',review_state='pending' WHERE id=?", id);
        mvc.perform(patch("/api/v1/posts/{id}", id).with(authentication(identity(author))).with(csrf())
                        .contentType("application/json").content("{\"content\":\" 更新正文 \",\"category\":\"study\",\"imageIds\":[],\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("更新正文"))
                .andExpect(jsonPath("$.data.category").value("study")).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.author.id").doesNotExist()).andExpect(jsonPath("$.data.author.isAnonymous").value(true))
                .andExpect(jsonPath("$.data.status").value("hidden")).andExpect(jsonPath("$.data.reviewState").value("pending"));
        var after = jdbc.queryForMap("SELECT author_id,is_anonymous,anonymous_label,created_at,request_hash FROM xl_post WHERE id=?", id);
        assertEquals(original.get("author_id"), after.get("author_id"));
        assertEquals(original.get("is_anonymous"), after.get("is_anonymous"));
        assertEquals(original.get("anonymous_label"), after.get("anonymous_label"));
        assertEquals(original.get("created_at"), after.get("created_at"));
        assertArrayEquals((byte[]) original.get("request_hash"), (byte[]) after.get("request_hash"));
        assertEquals("更新正文", forum.create(input, author).get("content"));
    }

    @Test
    void editingRejectsMissingVersionEmptyPatchInvalidValuesAndProtectedFields() throws Exception {
        long id = create(true);
        for (String body : List.of("{\"content\":\"更新\"}", "{\"expectedVersion\":0}",
                "{\"content\":null,\"expectedVersion\":0}", "{\"content\":\"   \",\"expectedVersion\":0}",
                "{\"category\":\"unknown\",\"expectedVersion\":0}", "{\"content\":\"更新\",\"expectedVersion\":-1}",
                "{\"content\":\"更新\",\"anonymous\":false,\"expectedVersion\":0}",
                "{\"content\":\"更新\",\"authorId\":\"99\",\"expectedVersion\":0}",
                "{\"content\":\"更新\",\"status\":\"visible\",\"expectedVersion\":0}",
                "{\"content\":\"更新\",\"clientRequestId\":\"other\",\"expectedVersion\":0}")) {
            mvc.perform(patch("/api/v1/posts/{id}", id).with(authentication(identity(author))).with(csrf())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertEquals(0L, forum.detail(id, author).get("version"));
        assertEquals("测试帖子", forum.detail(id, author).get("content"));
    }

    @Test
    void editingRequiresOwnershipCurrentVersionPublishPermissionAndUndeletedPost() throws Exception {
        long id = create(false);
        String body = "{\"content\":\"更新\",\"expectedVersion\":0}";
        mvc.perform(patch("/api/v1/posts/{id}", id).with(authentication(identity(reader))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/posts/{id}", id).with(authentication(identity(author))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/posts/{id}", id).with(authentication(identity(author))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        jdbc.update("UPDATE xl_user SET restrict_publish=1 WHERE id=?", author.subjectId());
        mvc.perform(patch("/api/v1/posts/{id}", id).with(authentication(identity(author))).with(csrf())
                .contentType("application/json").content("{\"category\":\"study\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTION_RESTRICTED"));
        jdbc.update("UPDATE xl_user SET restrict_publish=0 WHERE id=?", author.subjectId());
        forum.deletePost(id, 1, author);
        mvc.perform(patch("/api/v1/posts/{id}", id).with(authentication(identity(author))).with(csrf())
                .contentType("application/json").content("{\"category\":\"study\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void editingImagesReplacesReferencesWhileOmittedImagesAndTextStayUnchanged() {
        long id = create(true);
        String first = image(author.subjectId());
        String second = image(author.subjectId());
        var attached = forum.update(id, new ForumModels.UpdatePost(null, null, List.of(first), 0L), author);
        assertEquals("测试帖子", attached.get("content"));
        assertEquals(first, ((Map<?, ?>) ((List<?>) attached.get("images")).getFirst()).get("imageId"));
        forum.update(id, new ForumModels.UpdatePost(null, "study", null, 1L), author);
        assertEquals(Long.parseLong(first), jdbc.queryForObject("SELECT image_id FROM xl_media_ref WHERE owner_type='post' AND owner_id=?", Long.class, id));
        forum.update(id, new ForumModels.UpdatePost(null, null, List.of(second), 2L), author);
        assertEquals(Long.parseLong(second), jdbc.queryForObject("SELECT image_id FROM xl_media_ref WHERE owner_type='post' AND owner_id=?", Long.class, id));
        String foreign = image(reader.subjectId());
        assertEquals(ErrorCode.IMAGE_FORBIDDEN, assertThrows(BusinessException.class, () -> forum.update(id,
                new ForumModels.UpdatePost("不能写入", null, List.of(foreign), 3L), author)).code());
        assertEquals("测试帖子", forum.detail(id, author).get("content"));
        assertEquals(3L, forum.detail(id, author).get("version"));
    }

    private String image(long owner) {
        String key = "forum-edit-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO xl_image_asset(owner_user_id,purpose,storage_key,media_type,byte_size,width,height,sha256,state,expires_at) VALUES(?,'post',?,'image/png',100,4,3,UNHEX(SHA2(UUID(),256)),'temporary',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))", owner, key);
        return jdbc.queryForObject("SELECT id FROM xl_image_asset WHERE storage_key=?", String.class, key);
    }

    private UsernamePasswordAuthenticationToken identity(AuthPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private long create(boolean anonymous) {
        return Long.parseLong(forum.create(new ForumModels.CreatePost("测试帖子", "campus", List.of(), anonymous,
                UUID.randomUUID().toString()), author).get("id").toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> comment(long post, AuthPrincipal actor, String reply, String content) {
        return (Map<String, Object>) forum.createComment(post, new ForumModels.CreateComment(content, reply,
                UUID.randomUUID().toString()), actor).get("comment");
    }

    private long user(String nickname) {
        String open = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('it-forum',?,?)", open, nickname);
        return jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='it-forum' AND wechat_open_id=?", Long.class, open);
    }

    private AuthPrincipal principal(String nickname) {
        long id = user(nickname);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,UNHEX(SHA2(UUID(),256)),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))", id);
        long session = jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?", Long.class, id);
        return new AuthPrincipal(id, session, "user");
    }
}
