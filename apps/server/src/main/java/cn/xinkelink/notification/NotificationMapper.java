package cn.xinkelink.notification;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Collection;

@Mapper
public interface NotificationMapper {
    @Select("<script>SELECT id FROM xl_user WHERE id IN <foreach collection='users' item='user' open='(' separator=',' close=')'>#{user}</foreach> ORDER BY id FOR UPDATE</script>")
    List<Long> lockUsers(@Param("users") Collection<Long> users);
    record NoticeRow(long id,long recipientId,String eventKey,String type,String eventKind,Long actorId,Long taskId,
                     Long announcementId,String targetType,Long targetId,String title,String summary,Instant readAt,Instant createdAt){}
    String COLUMNS="id,recipient_id,event_key,type,event_kind,actor_id,task_id,announcement_id,target_type,target_id,title,summary,read_at,created_at";
    @Select("SELECT "+COLUMNS+" FROM xl_user_notification WHERE id=#{id} AND active=1") NoticeRow find(long id);
    @Select("SELECT "+COLUMNS+" FROM xl_user_notification WHERE id=#{id} AND recipient_id=#{recipient} AND active=1 FOR UPDATE")
    NoticeRow findForUpdate(@Param("id") long id,@Param("recipient") long recipient);
    @Select("SELECT id FROM xl_user_notification WHERE recipient_id=#{recipient} AND event_key=#{key}")
    Long event(@Param("recipient") long recipient,@Param("key") String key);
    @Insert("""
        INSERT INTO xl_user_notification(recipient_id,event_key,type,event_kind,actor_id,task_id,announcement_id,target_type,target_id,title,summary)
        VALUES(#{recipient},#{key},#{type},#{kind},#{actor},#{task},#{announcement},#{targetType},#{targetId},#{title},#{summary})
        """) int insert(@Param("recipient") long recipient,@Param("key") String key,@Param("type") String type,
            @Param("kind") String kind,@Param("actor") Long actor,@Param("task") Long task,@Param("announcement") Long announcement,
            @Param("targetType") String targetType,@Param("targetId") Long targetId,@Param("title") String title,@Param("summary") String summary);
    @Select("<script>SELECT "+COLUMNS+" "+"""
        FROM xl_user_notification WHERE recipient_id=#{user} AND active=1 AND created_at &lt;= #{snapshot}
        <if test='type != null'>AND type=#{type}</if><if test='unread'>AND read_at IS NULL</if>
        <if test='at != null'>AND (created_at &lt; #{at} OR (created_at=#{at} AND id &lt; #{id}))</if>
        ORDER BY created_at DESC,id DESC LIMIT #{size}</script>
        """) List<NoticeRow> list(@Param("user") long user,@Param("type") String type,@Param("unread") boolean unread,
            @Param("snapshot") Instant snapshot,@Param("at") Instant at,@Param("id") long id,@Param("size") int size);
    @Update("UPDATE xl_user_notification SET read_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=#{id} AND recipient_id=#{user} AND active=1 AND read_at IS NULL")
    int read(@Param("id") long id,@Param("user") long user);
    @Select("""
        <script>SELECT id FROM xl_user_notification WHERE recipient_id=#{user} AND active=1 AND read_at IS NULL
        <if test='through != null'>AND id &lt;= #{through}</if> ORDER BY id</script>
        """) List<Long> unreadIds(@Param("user") long user,@Param("through") Long through);
    @Update("""
        <script>UPDATE xl_user_notification SET read_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),version=version+1
        WHERE recipient_id=#{user} AND active=1 AND read_at IS NULL AND id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
        """) int readIds(@Param("user") long user,@Param("ids") Collection<Long> ids);
    @Update("UPDATE xl_user_notification SET active=0,read_at=COALESCE(read_at,UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE event_key=#{key} AND active=1")
    int deactivate(String key);
    @Select("SELECT recipient_id FROM xl_user_notification WHERE event_key=#{key} AND active=1") List<Long> receivers(String key);
    @Select("""
        <script>SELECT (SELECT COUNT(*) FROM xl_message m JOIN xl_conversation_member cm ON cm.conversation_id=m.conversation_id
            WHERE cm.user_id=#{user} AND m.sender_id&lt;&gt;#{user} AND m.moderation_state='normal'
              AND m.sequence_no&gt;GREATEST(cm.read_through_seq,cm.cleared_through_seq)) AS messages,
          (SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=#{user} AND active=1 AND read_at IS NULL AND type='comment') AS comments,
          (SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=#{user} AND active=1 AND read_at IS NULL AND type='reaction') AS reactions,
          (SELECT COUNT(*) FROM xl_user_notification WHERE recipient_id=#{user} AND active=1 AND read_at IS NULL AND type='announcement') AS announcements</script>
        """) Map<String,Object> counts(long user);
    @Select("SELECT messages,interactions,announcements FROM xl_user_preference WHERE user_id=#{user}") Map<String,Object> preferences(long user);
    @Select("SELECT id FROM xl_user WHERE deleted_at IS NULL ORDER BY id LIMIT 5001") List<Long> recipients();
    @Select("SELECT COUNT(*) FROM xl_user WHERE id=#{user} AND deleted_at IS NULL") int recipientExists(long user);

    record PostTarget(long id,long authorId,String content,boolean anonymous,String anonymousLabel,boolean available){}
    @Select("""
        SELECT id,author_id,content,is_anonymous,anonymous_label,
        (deleted_at IS NULL AND status='visible' AND review_state='normal') AS available FROM xl_post WHERE id=#{id}
        """) PostTarget post(long id);
    record CommentTarget(long id,long authorId,long postId,String content,Long replyToId,boolean available){}
    @Select("""
        SELECT c.id,c.author_id,c.target_id AS post_id,c.content,c.reply_to_id,
          (c.deleted_at IS NULL AND c.status='visible' AND p.deleted_at IS NULL AND p.status='visible'
           AND p.review_state='normal' AND (c.root_id IS NULL OR (r.deleted_at IS NULL AND r.status='visible'))) AS available
        FROM xl_comment c JOIN xl_post p ON c.target_type='post' AND p.id=c.target_id
        LEFT JOIN xl_comment r ON r.id=c.root_id WHERE c.id=#{id}
        """) CommentTarget comment(long id);
    @Select("SELECT title,body,status,deleted_at AS deletedAt FROM xl_announcement WHERE id=#{id}") Map<String,Object> announcement(long id);
    @Select("SELECT title,body,status FROM xl_notification_task WHERE id=#{id}") Map<String,Object> task(long id);
}
