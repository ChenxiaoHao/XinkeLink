package cn.xinkelink.media.persistence;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Mapper
public interface MediaMapper {
    String COLUMNS = "id,owner_user_id,purpose,storage_key,media_type,byte_size,width,height,state,expires_at,deleted_at";

    record Asset(long id, long ownerUserId, String purpose, String storageKey, String mediaType,
                 int byteSize, int width, int height, String state, Instant expiresAt, Instant deletedAt) {
        @Override public String toString() { return "Asset[id=" + id + ", state=" + state + "]"; }
    }
    record Ref(String ownerType, long ownerId, long imageId, int position) {}
    record Parent(long id, long ownerId, boolean publicVisible, boolean deleted) {}

    @Select("SELECT " + COLUMNS + " FROM xl_image_asset WHERE id=#{id}") Asset find(long id);
    @Select("SELECT " + COLUMNS + " FROM xl_image_asset WHERE id=#{id} FOR UPDATE") Asset lock(long id);
    @Insert("""
            INSERT INTO xl_image_asset(owner_user_id,purpose,storage_key,media_type,byte_size,width,height,sha256,expires_at)
            VALUES (#{owner},#{purpose},#{key},'image/png',#{size},#{width},#{height},#{sha256},#{expires})
            """)
    void insert(@Param("owner") long owner, @Param("purpose") String purpose, @Param("key") String key,
                @Param("size") long size, @Param("width") int width, @Param("height") int height,
                @Param("sha256") byte[] sha256, @Param("expires") Instant expires);
    @Select("SELECT " + COLUMNS + " FROM xl_image_asset WHERE storage_key=#{key}") Asset byKey(String key);
    @Update("UPDATE xl_image_asset SET state='ready',expires_at=NULL,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}") void ready(long id);
    @Update("UPDATE xl_image_asset SET state=#{state},deleted_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    void state(@Param("id") long id, @Param("state") String state);
    @Select("SELECT owner_type,owner_id,image_id,position FROM xl_media_ref WHERE image_id=#{id}") List<Ref> refs(long id);
    @Select("""
            <script>SELECT owner_type,owner_id,image_id,position FROM xl_media_ref
            WHERE owner_type=#{type} AND owner_id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            ORDER BY owner_id,position</script>
            """)
    List<Ref> refsForParents(@Param("type") String type, @Param("ids") Collection<Long> ids);
    @Select("""
            <script>SELECT id,owner_user_id,purpose,storage_key,media_type,byte_size,width,height,state,expires_at,deleted_at
            FROM xl_image_asset WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
            """) List<Asset> assets(@Param("ids") Collection<Long> ids);
    @Delete("DELETE FROM xl_media_ref WHERE owner_type=#{type} AND owner_id=#{id}") void detach(@Param("type") String type, @Param("id") long id);
    @Insert("INSERT INTO xl_media_ref(owner_type,owner_id,position,image_id) VALUES (#{type},#{parent},#{position},#{image})")
    void ref(@Param("type") String type, @Param("parent") long parent, @Param("position") int position, @Param("image") long image);

    @Select("""
            <script>SELECT id,author_id AS owner_id,
            (deleted_at IS NULL AND status='visible' AND review_state='normal') AS public_visible,
            deleted_at IS NOT NULL AS deleted FROM xl_post WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
            """) List<Parent> posts(@Param("ids") Collection<Long> ids);
    @Select("SELECT id FROM xl_user WHERE id=#{owner} AND deleted_at IS NULL FOR UPDATE") Long lockOwner(long owner);
    @Select("""
            <script>SELECT id,id owner_id,(deleted_at IS NULL) public_visible,(deleted_at IS NOT NULL) deleted
            FROM xl_user WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
            """) List<Parent> avatars(@Param("ids") Collection<Long> ids);
    @Select("SELECT COUNT(*) FROM xl_report WHERE id=#{id}") int reportExists(long id);
    @Select("""
            SELECT COUNT(*) FROM xl_auth_session s JOIN xl_admin a ON a.id=s.subject_id
            WHERE s.id=#{sid} AND s.subject_type='admin' AND s.subject_id=#{uid} AND s.revoked_at IS NULL
            AND s.expires_at>#{now} AND a.status='active' AND a.role='ADMIN'
            """) int validAdminSession(@Param("uid") long uid,@Param("sid") long sid,@Param("now") Instant now);
    @Select("""
            SELECT COUNT(*) FROM xl_auth_session s JOIN xl_user u ON s.subject_id=u.id
            WHERE s.id=#{sid} AND s.subject_type='user' AND s.subject_id=#{uid} AND s.revoked_at IS NULL
            AND s.expires_at>#{now} AND u.deleted_at IS NULL AND u.ban_state<>'permanent'
            AND (u.ban_state<>'temporary' OR u.ban_until<=#{now})
            """) int validSession(@Param("uid") long uid, @Param("sid") long sid, @Param("now") Instant now);
}
