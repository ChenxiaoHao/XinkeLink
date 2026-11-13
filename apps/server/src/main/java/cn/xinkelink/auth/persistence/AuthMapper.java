package cn.xinkelink.auth.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface AuthMapper {
    // 列顺序与record构造参数一致；时间使用MyBatis的InstantTypeHandler和UTC连接。
    String USER_COLUMNS = """
            id, nickname, avatar_image_id AS avatarImageId, bio, major,
            class_name AS className, enrollment_year AS enrollmentYear, ban_state AS banState,
            ban_until AS banUntil, restrict_publish AS restrictPublish,
            restrict_comment AS restrictComment, restrict_message AS restrictMessage,
            deleted_at AS deletedAt, version
            """;
    String ADMIN_COLUMNS = """
            id, login_name AS loginName, password_hash AS passwordHash, display_name AS displayName,
            role, status, password_changed_at AS passwordChangedAt, last_login_at AS lastLoginAt
            """;
    String SESSION_COLUMNS = """
            id, subject_type AS subjectType, subject_id AS subjectId, refresh_hash AS refreshHash,
            refresh_generation AS refreshGeneration, csrf_hash AS csrfHash, expires_at AS expiresAt,
            revoked_at AS revokedAt, last_used_at AS lastUsedAt, version
            """;

    @Select("SELECT " + USER_COLUMNS + " FROM xl_user WHERE id = #{id}")
    UserAccount findUser(@Param("id") long id);

    @Select("SELECT " + USER_COLUMNS + """
             FROM xl_user WHERE wechat_app_id = #{appId} AND wechat_open_id = #{openId}
            """)
    UserAccount findWechatUser(@Param("appId") String appId, @Param("openId") String openId);

    // 唯一键保证并发登录共用同一账号，不覆盖既有资料、限制或软删除状态。
    @Insert("""
            INSERT INTO xl_user (wechat_app_id, wechat_open_id) VALUES (#{appId}, #{openId})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int upsertWechatUser(@Param("appId") String appId, @Param("openId") String openId);

    @Update("""
            UPDATE xl_user SET last_active_at = #{now}, updated_at = #{now}, version = version + 1
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int touchUser(@Param("id") long id, @Param("now") Instant now);

    @Select("SELECT " + ADMIN_COLUMNS + " FROM xl_admin WHERE id = #{id}")
    AdminAccount findAdmin(@Param("id") long id);

    @Select("SELECT " + ADMIN_COLUMNS + " FROM xl_admin WHERE login_name = #{account}")
    AdminAccount findAdminByLogin(@Param("account") String account);

    @Update("""
            UPDATE xl_admin SET last_login_at = #{now}, updated_at = #{now}, version = version + 1
            WHERE id = #{id}
            """)
    int touchAdmin(@Param("id") long id, @Param("now") Instant now);

    @Insert("""
            INSERT INTO xl_auth_session (subject_type, subject_id, refresh_hash, csrf_hash, expires_at)
            VALUES (#{subjectType}, #{subjectId}, #{refreshHash,jdbcType=BINARY},
                    #{csrfHash,jdbcType=BINARY}, #{expiresAt})
            """)
    int insertSession(@Param("subjectType") String subjectType, @Param("subjectId") long subjectId,
            @Param("refreshHash") byte[] refreshHash, @Param("csrfHash") byte[] csrfHash,
            @Param("expiresAt") Instant expiresAt);

    @Select("SELECT " + SESSION_COLUMNS + " FROM xl_auth_session WHERE id = #{id}")
    AuthSession findSession(@Param("id") long id);

    @Select("SELECT " + SESSION_COLUMNS + """
             FROM xl_auth_session WHERE refresh_hash = #{hash,jdbcType=BINARY} FOR UPDATE
            """)
    AuthSession findSessionByRefreshHashForUpdate(@Param("hash") byte[] hash);

    // 轮换不延长会话到期时间；旧摘要只允许成功更新一次。
    @Update("""
            UPDATE xl_auth_session
            SET refresh_hash = #{newHash,jdbcType=BINARY}, csrf_hash = #{csrfHash,jdbcType=BINARY},
                refresh_generation = refresh_generation + 1, version = version + 1,
                last_used_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND revoked_at IS NULL AND expires_at > #{now}
              AND refresh_hash = #{expectedHash,jdbcType=BINARY}
            """)
    int rotateSession(@Param("id") long id, @Param("expectedHash") byte[] expectedHash,
            @Param("newHash") byte[] newHash, @Param("csrfHash") byte[] csrfHash,
            @Param("now") Instant now);

    @Update("""
            UPDATE xl_auth_session SET revoked_at = #{now}, updated_at = #{now}, version = version + 1
            WHERE id = #{id} AND revoked_at IS NULL
            """)
    int revokeSession(@Param("id") long id, @Param("now") Instant now);
}
