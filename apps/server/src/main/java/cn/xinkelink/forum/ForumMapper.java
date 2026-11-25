package cn.xinkelink.forum;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static cn.xinkelink.forum.ForumModels.*;

@Mapper
public interface ForumMapper {
    // 片段保留边界空格，避免文本块裁剪尾部空格后拼成SELECTid或ANDc。
    String POST_COLUMNS = " id,author_id,category,content,is_anonymous AS anonymous,anonymous_label,status,review_state,request_hash,created_at,version,deleted_at ";
    String COMMENT_COLUMNS = " id,target_type,target_id,author_id,root_id,reply_to_id,content,status,request_hash,created_at,version,deleted_at ";
    String PUBLIC_POST = " deleted_at IS NULL AND status='visible' AND review_state='normal' ";
    String PUBLIC_COMMENT = " c.deleted_at IS NULL AND c.status='visible' AND (c.root_id IS NULL OR EXISTS (SELECT 1 FROM xl_comment r WHERE r.id=c.root_id AND r.root_id IS NULL AND r.target_type=c.target_type AND r.target_id=c.target_id AND r.deleted_at IS NULL AND r.status='visible')) ";

    @Select("SELECT id FROM xl_user WHERE id=#{owner} FOR UPDATE")
    Long lockUser(long owner);

    @Select("SELECT " + POST_COLUMNS + " FROM xl_post WHERE id=#{id}")
    PostRow post(long id);

    @Select("SELECT " + POST_COLUMNS + " FROM xl_post WHERE id=#{id} FOR UPDATE")
    PostRow lockPost(long id);

    @Select("SELECT " + POST_COLUMNS + " FROM xl_post WHERE author_id=#{owner} AND client_request_id=#{request}")
    PostRow requestedPost(@Param("owner") long owner, @Param("request") String request);

    @Insert("""
            INSERT INTO xl_post(author_id,category,content,is_anonymous,anonymous_label,client_request_id,request_hash)
            VALUES(#{owner},#{input.category},#{input.content},#{input.anonymous},#{label},#{input.clientRequestId},#{hash,jdbcType=BINARY})
            """)
    int insertPost(@Param("owner") long owner, @Param("input") CreatePost input,
            @Param("label") String label, @Param("hash") byte[] hash);

    @Update("""
            UPDATE xl_post SET content=COALESCE(#{input.content},content),category=COALESCE(#{input.category},category),
            updated_at=CURRENT_TIMESTAMP(3),version=version+1
            WHERE id=#{id} AND version=#{input.expectedVersion} AND deleted_at IS NULL
            """)
    int updatePost(@Param("id") long id, @Param("input") UpdatePost input);

    @Select("""
            <script>SELECT p.id,p.author_id,p.category,p.content,p.is_anonymous AS anonymous,p.anonymous_label,
            p.status,p.review_state,p.request_hash,p.created_at,p.version,p.deleted_at FROM xl_post p
            WHERE p.deleted_at IS NULL AND p.created_at &lt;= #{snapshot}
            <choose><when test="owner != null">AND p.author_id=#{owner}</when>
            <otherwise>AND p.status='visible' AND p.review_state='normal'</otherwise></choose>
            <if test="category != null">AND p.category=#{category}</if>
            <if test="keyword != null">AND (p.content LIKE #{keyword} ESCAPE '!'
            OR (p.is_anonymous=0 AND EXISTS(SELECT 1 FROM xl_user u WHERE u.id=p.author_id AND u.deleted_at IS NULL AND u.nickname LIKE #{keyword} ESCAPE '!')))</if>
            <if test="at != null">AND (p.created_at &lt; #{at} OR (p.created_at=#{at} AND p.id &lt; #{id}))</if>
            ORDER BY p.created_at DESC,p.id DESC LIMIT #{limit}</script>
            """)
    List<PostRow> posts(@Param("owner") Long owner, @Param("category") String category,
            @Param("keyword") String keyword, @Param("snapshot") Instant snapshot, @Param("at") Instant at,
            @Param("id") long id, @Param("limit") int limit);

    @Select("""
            <script>SELECT """ + POST_COLUMNS + """
             FROM xl_post WHERE """ + PUBLIC_POST + """
             AND id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<PostRow> publicPosts(@Param("ids") Collection<Long> ids);

    @Select("""
            <script>SELECT p.id,
            (SELECT COUNT(*) FROM xl_like l WHERE l.target_type='post' AND l.target_id=p.id) AS like_count,
            (SELECT COUNT(*) FROM xl_comment c WHERE c.target_type='post' AND c.target_id=p.id AND """ + PUBLIC_COMMENT + """
            ) AS comment_count
            FROM xl_post p WHERE p.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<PublicPostMetric> publicPostMetrics(@Param("ids") Collection<Long> ids);

    @Select("""
            <script>SELECT p.id,
            EXISTS(SELECT 1 FROM xl_like l WHERE l.target_type='post' AND l.target_id=p.id AND l.user_id=#{viewer}) AS liked,
            EXISTS(SELECT 1 FROM xl_favorite f WHERE f.target_type='post' AND f.target_id=p.id AND f.user_id=#{viewer}) AS favorited
            FROM xl_post p WHERE p.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<PostViewerState> postViewerStates(@Param("ids") Collection<Long> ids, @Param("viewer") Long viewer);

    @Update("UPDATE xl_post SET deleted_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND deleted_at IS NULL")
    int deletePost(long id);

    @Update("UPDATE xl_comment SET deleted_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE target_type='post' AND target_id=#{id} AND deleted_at IS NULL")
    int deletePostComments(long id);

    @Select("SELECT " + COMMENT_COLUMNS + " FROM xl_comment WHERE id=#{id}")
    CommentRow comment(long id);

    @Select("SELECT " + COMMENT_COLUMNS + " FROM xl_comment WHERE author_id=#{owner} AND client_request_id=#{request}")
    CommentRow requestedComment(@Param("owner") long owner, @Param("request") String request);

    @Insert("""
            INSERT INTO xl_comment(target_type,target_id,author_id,root_id,reply_to_id,content,client_request_id,request_hash)
            VALUES('post',#{post},#{owner},#{root},#{reply},#{input.content},#{input.clientRequestId},#{hash,jdbcType=BINARY})
            """)
    int insertComment(@Param("post") long post, @Param("owner") long owner, @Param("root") Long root,
            @Param("reply") Long reply, @Param("input") CreateComment input, @Param("hash") byte[] hash);

    @Select("""
            <script>SELECT """ + COMMENT_COLUMNS + """
             FROM xl_comment WHERE target_type='post' AND target_id=#{post} AND deleted_at IS NULL AND status='visible'
            AND created_at &lt;= #{snapshot}
            <choose><when test="root != null">AND root_id=#{root}
            <if test="at != null">AND (created_at &gt; #{at} OR (created_at=#{at} AND id &gt; #{id}))</if>
            ORDER BY created_at ASC,id ASC</when><otherwise>AND root_id IS NULL
            <if test="at != null">AND (created_at &lt; #{at} OR (created_at=#{at} AND id &lt; #{id}))</if>
            ORDER BY created_at DESC,id DESC</otherwise></choose> LIMIT #{limit}</script>
            """)
    List<CommentRow> comments(@Param("post") long post, @Param("root") Long root, @Param("snapshot") Instant snapshot,
            @Param("at") Instant at, @Param("id") long id, @Param("limit") int limit);

    // 一次窗口查询给每个一级评论取首条回复。
    @Select("""
            <script>SELECT """ + COMMENT_COLUMNS + """
             FROM (SELECT c.*,ROW_NUMBER() OVER(PARTITION BY root_id ORDER BY created_at,id) AS rn
            FROM xl_comment c WHERE c.deleted_at IS NULL AND c.status='visible' AND c.created_at &lt;= #{snapshot}
            AND c.root_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>) ranked
            WHERE rn=1</script>
            """)
    List<CommentRow> previewReplies(@Param("ids") Collection<Long> ids, @Param("snapshot") Instant snapshot);

    @Select("""
            <script>SELECT """ + COMMENT_COLUMNS + """
             FROM xl_comment WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<CommentRow> commentsByIds(@Param("ids") Collection<Long> ids);

    @Select("""
            <script>SELECT c.id,
            (SELECT COUNT(*) FROM xl_like l WHERE l.target_type='comment' AND l.target_id=c.id) AS like_count,
            (SELECT COUNT(*) FROM xl_comment r WHERE r.root_id=c.id AND r.deleted_at IS NULL AND r.status='visible') AS reply_count,
            EXISTS(SELECT 1 FROM xl_like l WHERE l.target_type='comment' AND l.target_id=c.id AND l.user_id=#{viewer}) AS liked
            FROM xl_comment c WHERE c.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<CommentMetric> commentMetrics(@Param("ids") Collection<Long> ids, @Param("viewer") Long viewer);

    @Select("SELECT COUNT(*) FROM xl_comment c WHERE c.target_type='post' AND c.target_id=#{post} AND " + PUBLIC_COMMENT)
    long commentCount(long post);

    @Update("UPDATE xl_comment SET deleted_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE deleted_at IS NULL AND (id=#{id} OR root_id=#{id})")
    int deleteComment(long id);

    @Insert("INSERT IGNORE INTO xl_like(user_id,target_type,target_id) VALUES(#{viewer},#{type},#{id})")
    int addLike(@Param("viewer") long viewer, @Param("type") String type, @Param("id") long id);

    @Delete("DELETE FROM xl_like WHERE user_id=#{viewer} AND target_type=#{type} AND target_id=#{id}")
    int removeLike(@Param("viewer") long viewer, @Param("type") String type, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM xl_like WHERE target_type=#{type} AND target_id=#{id}")
    long likes(@Param("type") String type, @Param("id") long id);

    @Insert("INSERT IGNORE INTO xl_favorite(user_id,target_type,target_id) VALUES(#{viewer},'post',#{id})")
    int addFavorite(@Param("viewer") long viewer, @Param("id") long id);

    @Delete("DELETE FROM xl_favorite WHERE user_id=#{viewer} AND target_type='post' AND target_id=#{id}")
    int removeFavorite(@Param("viewer") long viewer, @Param("id") long id);
}
