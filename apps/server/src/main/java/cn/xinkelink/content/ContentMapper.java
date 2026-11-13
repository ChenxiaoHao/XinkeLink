package cn.xinkelink.content;

import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.auth.persistence.UserAccount;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ContentMapper {
    @Select("SELECT UTC_TIMESTAMP(3)")
    Instant currentTime();
    @Select("SELECT " + AuthMapper.USER_COLUMNS + " FROM xl_user WHERE id=#{id} FOR UPDATE")
    UserAccount userForUpdate(@Param("id") long id);

    @Select("<script>SELECT " + AuthMapper.USER_COLUMNS + " FROM xl_user WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<UserAccount> users(@Param("ids") Collection<Long> ids);

    record FavoriteRow(long id, Instant createdAt) {}
    @Select("""
            <script>SELECT target_id AS id,created_at AS createdAt FROM xl_favorite
            WHERE user_id=#{userId} AND target_type=#{type} AND created_at &lt;= #{snapshot}
            <if test='at != null'>AND (created_at &lt; #{at} OR (created_at=#{at} AND target_id &lt; #{id}))</if>
            ORDER BY created_at DESC,target_id DESC LIMIT #{size}</script>
            """)
    List<FavoriteRow> favorites(@Param("userId") long userId,@Param("type") String type,
            @Param("snapshot") Instant snapshot,@Param("at") Instant at,@Param("id") long id,@Param("size") int size);

    @Select("""
            <script>SELECT target_id AS id,created_at AS createdAt FROM xl_like
            WHERE user_id=#{userId} AND target_type=#{type} AND created_at &lt;= #{snapshot}
            <if test='at != null'>AND (created_at &lt; #{at} OR (created_at=#{at} AND target_id &lt; #{id}))</if>
            ORDER BY created_at DESC,target_id DESC LIMIT #{size}</script>
            """)
    List<FavoriteRow> likes(@Param("userId") long userId,@Param("type") String type,
            @Param("snapshot") Instant snapshot,@Param("at") Instant at,@Param("id") long id,@Param("size") int size);

    record Entry(long id, Instant createdAt) {}
    @Select("""
            <script>SELECT id,created_at AS createdAt FROM xl_post WHERE author_id=#{author}
            AND deleted_at IS NULL AND status='visible' AND review_state='normal' AND is_anonymous=0
            AND created_at &lt;= #{snapshot}
            <if test='at != null'>AND (created_at &lt; #{at} OR (created_at=#{at} AND id &lt; #{id}))</if>
            ORDER BY created_at DESC,id DESC LIMIT #{size}</script>
            """)
    List<Entry> authorPosts(@Param("author") long author,@Param("snapshot") Instant snapshot,
            @Param("at") Instant at,@Param("id") long id,@Param("size") int size);


    @Select("""
            SELECT
              (SELECT COUNT(*) FROM xl_post WHERE author_id=#{user} AND deleted_at IS NULL) AS postCount,
              (SELECT COUNT(*) FROM xl_favorite WHERE user_id=#{user} AND target_type='post') AS favoritePostCount,
              (SELECT COUNT(*) FROM xl_like WHERE user_id=#{user} AND target_type='post') AS likedPostCount,
              (SELECT COUNT(*) FROM xl_like l JOIN xl_post p ON l.target_type='post' AND l.target_id=p.id
                WHERE p.author_id=#{user} AND p.deleted_at IS NULL AND p.status='visible' AND p.review_state='normal') AS receivedLikeCount
            """)
    java.util.Map<String,Object> summary(@Param("user") long user);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM xl_post WHERE author_id=#{user} AND deleted_at IS NULL
                AND status='visible' AND review_state='normal' AND is_anonymous=0) AS postCount,
              (SELECT COUNT(*) FROM xl_like l JOIN xl_post p ON l.target_type='post' AND l.target_id=p.id
                WHERE p.author_id=#{user} AND p.deleted_at IS NULL AND p.status='visible'
                AND p.review_state='normal' AND p.is_anonymous=0) AS likeCount
            """)
    java.util.Map<String,Object> publicStats(@Param("user") long user);
}
