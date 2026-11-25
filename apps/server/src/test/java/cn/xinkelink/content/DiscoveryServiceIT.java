package cn.xinkelink.content;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"db","auth"})
@Transactional
class DiscoveryServiceIT {

    @Autowired DiscoveryService discovery;
    @Autowired JdbcTemplate jdbc;
    AuthPrincipal owner;
    long publicPost,anonymousPost,hiddenPost;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("xinkelink.auth.jwt.secret-base64",()->Base64.getEncoder().encodeToString(new byte[32]));}
    @BeforeEach void fixture(){
        String open="discovery-"+UUID.randomUUID();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES('it-day3-discovery',?,'资料同学')",open);
        long id=jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id='it-day3-discovery' AND wechat_open_id=?",Long.class,open);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('user',?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",id,ContentSupport.hash(UUID.randomUUID().toString()));
        long sid=jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE subject_type='user' AND subject_id=?",Long.class,id);
        owner=new AuthPrincipal(id,sid,"user");
        publicPost=post(false,"visible","共享课程资料");
        anonymousPost=post(true,"visible","匿名的学习记录");
        hiddenPost=post(false,"hidden","隐藏正文不可泄露");
    }
    @Test void publicProfileAndAuthorPostsNeverIncludeAnonymousOwnership(){
        Map<String,Object> profile=discovery.profile(owner.subjectId());
        assertEquals(1L,((Number)((Map<?,?>)profile.get("stats")).get("postCount")).longValue());
        assertFalse(profile.containsKey("wechatOpenId"));
        var posts=discovery.authorPosts(owner.subjectId(),null,20,null);
        assertEquals(1,posts.items().size());
        assertEquals(Long.toString(publicPost),posts.items().getFirst().get("id"));
        assertEquals(3L,((Number)discovery.summary(owner).get("postCount")).longValue());
    }
    @Test void publicProfileUsesTheSameAvatarProjectionAsPostAuthors(){
        assertNull(discovery.profile(owner.subjectId()).get("avatar"));
        String key=UUID.randomUUID()+".png";
        jdbc.update("INSERT INTO xl_image_asset(owner_user_id,purpose,storage_key,media_type,byte_size,width,height,sha256,state) VALUES(?,'avatar',?,'image/png',100,128,128,?,'ready')",owner.subjectId(),key,new byte[32]);
        long image=jdbc.queryForObject("SELECT id FROM xl_image_asset WHERE storage_key=?",Long.class,key);
        jdbc.update("UPDATE xl_user SET avatar_image_id=? WHERE id=?",image,owner.subjectId());
        jdbc.update("INSERT INTO xl_media_ref(owner_type,owner_id,position,image_id) VALUES('user_avatar',?,0,?)",owner.subjectId(),image);
        var profile=discovery.profile(owner.subjectId());
        assertNotNull(profile.get("avatar"),"公开主页不能把已有头像硬编码成null");
        var avatar=(Map<?,?>)profile.get("avatar");
        assertEquals(Long.toString(image),avatar.get("imageId"));
        assertTrue(avatar.get("url").toString().startsWith("/api/v1/images/"+image+"/content?grant="));
        var post=discovery.authorPosts(owner.subjectId(),null,20,null).items().getFirst();
        assertEquals(avatar.get("imageId"),((Map<?,?>)((Map<?,?>)post.get("author")).get("avatar")).get("imageId"));
        assertFalse(profile.containsKey("wechatOpenId"));
        jdbc.update("UPDATE xl_image_asset SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?",image);
        assertNull(discovery.profile(owner.subjectId()).get("avatar"),"已失效资源不应重新暴露");
    }
    @Test void favoritesKeepUnavailableTargetsAndUseTheSameIds(){
        for(long id:List.of(publicPost,hiddenPost))jdbc.update("INSERT INTO xl_favorite(user_id,target_type,target_id) VALUES(?,'post',?)",owner.subjectId(),id);
        var page=discovery.favorites("post",null,1,owner);
        assertTrue(page.hasMore());
        assertEquals(Long.toString(hiddenPost),page.items().getFirst().get("targetId"));
        assertEquals("unavailable",page.items().getFirst().get("availability"));
        assertNull(page.items().getFirst().get("item"));
        var second=discovery.favorites("post",page.nextCursor(),1,owner);
        assertEquals(Long.toString(publicPost),second.items().getFirst().get("targetId"));
        assertEquals("available",second.items().getFirst().get("availability"));
        assertEquals(ErrorCode.VALIDATION_FAILED,assertThrows(BusinessException.class,()->discovery.favorites("product",page.nextCursor(),1,owner)).code());
    }
    @Test void searchReturnsEmptyPagesAndRejectsBlankOrWrongDomainFilters(){
        var response=discovery.search(" 不存在的词 ","posts",null,null,null,null,20,null);
        assertEquals("不存在的词",response.get("q"));
        assertTrue(((cn.xinkelink.common.api.CursorPage<?>)response.get("result")).items().isEmpty());
        assertThrows(BusinessException.class,()->discovery.search(" ","posts",null,null,null,null,20,null));
        assertThrows(BusinessException.class,()->discovery.search("词","posts",null,null,"available",null,20,null));
    }
    private long post(boolean anonymous,String status,String content){
        String request=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_post(author_id,category,content,is_anonymous,anonymous_label,status,client_request_id,request_hash) VALUES(?,'study',?,?,?,?,?,?)",
                owner.subjectId(),content,anonymous,anonymous?"匿名同学测试":null,status,request,new byte[32]);
        return jdbc.queryForObject("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?",Long.class,owner.subjectId(),request);
    }
}
