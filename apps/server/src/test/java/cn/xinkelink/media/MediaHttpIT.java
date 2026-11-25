package cn.xinkelink.media;

import cn.xinkelink.auth.ratelimit.AuthRateLimiter;
import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.storage.ImageUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "auth"})
@Import(MediaHttpIT.Adapters.class)
@Transactional
class MediaHttpIT {

    private static final Path DIRECTORY = temporaryDirectory();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;
    @Autowired MediaService media;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("XINKELINK_IMAGE_DIRECTORY", DIRECTORY::toString);
    }

    @Test
    void imageFailureUsesJsonErrorEvenWhenClientAcceptsPng() throws Exception {
        mvc.perform(get("/api/v1/images/1/content").param("grant", "invalid-grant").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void uploadsVerifiedImageWithoutExposingOriginalNameOrStorageLocation() throws Exception {
        String token = user();
        var result = mvc.perform(multipart("/api/v1/images")
                        .file(new MockMultipartFile("file", "../../identity.jpg", "text/plain", picture("jpeg", 3, 2)))
                        .param("purpose", "post").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.mediaType").value("image/png"))
                .andExpect(jsonPath("$.data.width").value(3)).andExpect(jsonPath("$.data.height").value(2))
                .andExpect(jsonPath("$.data.state").value("temporary"))
                .andExpect(content().string(not(containsString("identity"))))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).at("/data/id").stringValue();
        var access = mvc.perform(get("/api/v1/images/" + id + "/access").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String url = json.readTree(access.getResponse().getContentAsString()).at("/data/url").stringValue();
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/v1/images/" + id + "/access")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/images/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isForbidden());
    }

    @Test
    void refusesFakeImagesActualOversizeAndUnsupportedPurpose() throws Exception {
        String token = user();
        mvc.perform(multipart("/api/v1/images").file(new MockMultipartFile("file", "fake.png", "image/png", "not image".getBytes()))
                        .param("purpose", "post").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/v1/images").file(new MockMultipartFile("file", "huge.png", "image/png", new byte[10 * 1024 * 1024 + 1]))
                        .param("purpose", "post").header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/v1/images").file(new MockMultipartFile("file", "ok.png", "image/png", picture("png", 1, 1)))
                        .param("purpose", "unsupported").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_TARGET"));
    }

    @Test
    void anonymousPublicGrantStopsWorkingWhenParentIsHiddenAndPrivateGrantTracksSession() throws Exception {
        String token = user(); AuthPrincipal owner = principal(token);
        String image = image(owner, "post");
        long post = post(owner.subjectId(), true);
        media.bind(owner.subjectId(), "post", List.of(image), "post", post);
        Map<String, Object> access = media.access(Long.parseLong(image), null);
        assertEquals(java.util.Set.of("imageId", "url", "expiresAt", "width", "height"), access.keySet());
        String url = (String) access.get("url");
        mvc.perform(get(url)).andExpect(status().isOk());
        jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?", post);
        mvc.perform(get(url)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/images/" + image + "/access")).andExpect(status().isForbidden());
        String privateUrl = (String) media.access(Long.parseLong(image), owner).get("url");
        mvc.perform(get(privateUrl)).andExpect(status().isOk());
        jdbc.update("UPDATE xl_auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE id=?", owner.sessionId());
        mvc.perform(get(privateUrl)).andExpect(status().isUnauthorized());
    }

    @Test
    void boundImageCannotBeReusedAcrossAnonymousAndIdentifiedPostsEvenAfterDetach() throws Exception {
        AuthPrincipal owner = principal(user());
        String image = image(owner, "post");
        long first = post(owner.subjectId(), true), second = post(owner.subjectId(), false);
        media.bind(owner.subjectId(), "post", List.of(image), "post", first);
        assertEquals(ErrorCode.IMAGE_FORBIDDEN, assertThrows(BusinessException.class,
                () -> media.bind(owner.subjectId(), "post", List.of(image), "post", second)).code());
        media.detach("post", first);
        assertEquals(ErrorCode.IMAGE_FORBIDDEN, assertThrows(BusinessException.class,
                () -> media.access(Long.parseLong(image), owner)).code());
        assertEquals(ErrorCode.IMAGE_FORBIDDEN, assertThrows(BusinessException.class,
                () -> media.bind(owner.subjectId(), "post", List.of(image), "post", second)).code());
    }

    @Test
    void detachPreservesReportSnapshotAndDeleteRefusesAllReferencedImages() throws Exception {
        AuthPrincipal owner = principal(user()); String image = image(owner, "post");
        long post = post(owner.subjectId(), false);
        media.bind(owner.subjectId(), "post", List.of(image), "post", post);
        jdbc.update("INSERT INTO xl_media_ref(owner_type,owner_id,position,image_id) VALUES ('report_snapshot',987654,0,?)", image);
        assertEquals(ErrorCode.IMAGE_IN_USE, assertThrows(BusinessException.class, () -> media.delete(Long.parseLong(image), owner)).code());
        media.detach("post", post);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM xl_media_ref WHERE image_id=? AND owner_type='report_snapshot'", Integer.class, image));
        assertEquals(ErrorCode.IMAGE_IN_USE, assertThrows(BusinessException.class, () -> media.delete(Long.parseLong(image), owner)).code());
    }

    @Test
    void bindChecksOwnerPurposeCountExpiryAndBatchVisibility() throws Exception {
        AuthPrincipal owner = principal(user()), other = principal(user());
        long visible = post(owner.subjectId(), false), hidden = post(owner.subjectId(), true);
        String image = image(owner, "post"), foreign = image(other, "post"), avatar = image(owner, "avatar");
        assertEquals(ErrorCode.IMAGE_FORBIDDEN, assertThrows(BusinessException.class,
                () -> media.bind(owner.subjectId(), "post", List.of(foreign), "post", visible)).code());
        assertEquals(ErrorCode.IMAGE_FORBIDDEN, assertThrows(BusinessException.class,
                () -> media.bind(owner.subjectId(), "post", List.of(avatar), "post", visible)).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class,
                () -> media.bind(owner.subjectId(), "post", java.util.Collections.nCopies(10, image), "post", visible)).code());
        jdbc.update("UPDATE xl_image_asset SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?", image);
        assertEquals(ErrorCode.IMAGE_FORBIDDEN, assertThrows(BusinessException.class,
                () -> media.bind(owner.subjectId(), "post", List.of(image), "post", visible)).code());
        String valid = image(owner, "post"), hiddenImage = image(owner, "post");
        media.bind(owner.subjectId(), "post", List.of(valid), "post", visible);
        media.bind(owner.subjectId(), "post", List.of(hiddenImage), "post", hidden);
        jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?", hidden);
        var publicImages = media.images("post", List.of(visible, hidden), null);
        assertEquals(1, publicImages.get(visible).size()); assertEquals(0, publicImages.get(hidden).size());
        assertEquals(1, media.images("post", List.of(hidden), owner).get(hidden).size());
    }

    @Test
    void deletedParentAndTamperedGrantNeverReturnBytes() throws Exception {
        AuthPrincipal owner = principal(user()); String image = image(owner, "post"); long post = post(owner.subjectId(), false);
        media.bind(owner.subjectId(), "post", List.of(image), "post", post);
        String url = (String) media.access(Long.parseLong(image), null).get("url");
        mvc.perform(get(url + "invalid")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE xl_post SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?", post);
        mvc.perform(get(url)).andExpect(status().isForbidden());
        assertEquals(ErrorCode.IMAGE_FORBIDDEN, assertThrows(BusinessException.class, () -> media.access(Long.parseLong(image), owner)).code());
    }

    @Test
    void reportEvidenceIsPrivateToTheCaseAndAdminSessionIsRechecked() throws Exception {
        AuthPrincipal reporter=principal(user());AuthPrincipal target=principal(user());
        String evidence=image(reporter,"report");
        String key=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_report(reporter_id,target_type,target_id,target_user_id,reason_code,description,target_snapshot,client_request_id,request_hash) VALUES (?,'user',?,?,'other','测试说明','{}',?,?)",reporter.subjectId(),target.subjectId(),target.subjectId(),key,new byte[32]);
        long report=jdbc.queryForObject("SELECT id FROM xl_report WHERE reporter_id=? AND client_request_id=?",Long.class,reporter.subjectId(),key);
        jdbc.update("UPDATE xl_image_asset SET state='ready',expires_at=NULL WHERE id=?",evidence);
        jdbc.update("INSERT INTO xl_media_ref(owner_type,owner_id,position,image_id) VALUES('report_evidence',?,0,?)",report,evidence);
        String account="media-admin-"+UUID.randomUUID();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES(?,'test-only-hash','管理员',UTC_TIMESTAMP(3))",account);
        long adminId=jdbc.queryForObject("SELECT id FROM xl_admin WHERE login_name=?",Long.class,account);
        byte[] hash=new byte[32];new SecureRandom().nextBytes(hash);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('admin',?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR))",adminId,hash);
        long sid=jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE refresh_hash=?",Long.class,hash);
        AuthPrincipal admin=new AuthPrincipal(adminId,sid,"admin");
        assertThrows(BusinessException.class,()->media.reportImages(report,"report_evidence",reporter));
        assertThrows(BusinessException.class,()->media.access(Long.parseLong(evidence),reporter));
        assertThrows(BusinessException.class,()->media.access(Long.parseLong(evidence),null));
        String url=(String)media.reportImages(report,"report_evidence",admin).getFirst().get("url");
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        jdbc.update("UPDATE xl_auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE id=?",sid);
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    @Test void avatarBindingRejectsForeignImagesAndRevokesDetachedPublicLinks() throws Exception {
        String token=user();AuthPrincipal owner=principal(token),other=principal(user());
        String avatar=image(owner,"avatar"),foreign=image(other,"avatar"),wrong=image(owner,"post");
        media.bindAvatar(owner.subjectId(),Long.parseLong(avatar));
        assertEquals(avatar,media.avatars(List.of(owner.subjectId())).get(owner.subjectId()).get("imageId"));
        String url=(String)media.access(Long.parseLong(avatar),null).get("url");
        mvc.perform(get(url)).andExpect(status().isOk());
        assertThrows(BusinessException.class,()->media.bindAvatar(owner.subjectId(),Long.parseLong(foreign)));
        assertThrows(BusinessException.class,()->media.bindAvatar(owner.subjectId(),Long.parseLong(wrong)));
        media.bindAvatar(owner.subjectId(),null);
        mvc.perform(get(url)).andExpect(status().isForbidden());
    }
    @Test void adminHiddenImageGrantRechecksRealSessionAndNeverBecomesPublic() throws Exception {
        AuthPrincipal owner=principal(user());String image=image(owner,"post");long post=post(owner.subjectId(),true);
        media.bind(owner.subjectId(),"post",List.of(image),"post",post);
        jdbc.update("UPDATE xl_post SET status='hidden' WHERE id=?",post);
        String account="media-admin-hidden-"+UUID.randomUUID();
        jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES(?,'test-only-hash','管理员',UTC_TIMESTAMP(3))",account);
        long adminId=jdbc.queryForObject("SELECT id FROM xl_admin WHERE login_name=?",Long.class,account);
        byte[] hash=new byte[32];new SecureRandom().nextBytes(hash);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES('admin',?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR))",adminId,hash);
        long sid=jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE refresh_hash=?",Long.class,hash);AuthPrincipal admin=new AuthPrincipal(adminId,sid,"admin");
        String url=(String)media.images("post",List.of(post),admin).get(post).getFirst().get("url");
        mvc.perform(get(url)).andExpect(status().isOk());
        assertThrows(BusinessException.class,()->media.access(Long.parseLong(image),null));
        jdbc.update("UPDATE xl_auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE id=?",sid);
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }
    private String image(AuthPrincipal owner, String purpose) throws Exception {
        byte[] bytes = picture("png", 3, 2);
        return (String) media.upload(owner, purpose, new ImageUpload("private.png", "image/png", bytes.length, new ByteArrayInputStream(bytes))).get("id");
    }
    private long post(long owner, boolean anonymous) {
        String request = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO xl_post(author_id,category,content,is_anonymous,anonymous_label,client_request_id,request_hash) VALUES (?,'campus','media test',?,?,?,?)",
                owner, anonymous, anonymous ? "匿名同学" : null, request, new byte[32]);
        return jdbc.queryForObject("SELECT id FROM xl_post WHERE author_id=? AND client_request_id=?", Long.class, owner, request);
    }
    private AuthPrincipal principal(String token) {
        var jwt = tokens.decode(token);
        return new AuthPrincipal(Long.parseLong(jwt.getSubject()), Long.parseLong(jwt.getClaimAsString("sid")), "user");
    }

    private String user() {
        String identity = "media-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id) VALUES ('media-it',?)", identity);
        long userId = jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_open_id=?", Long.class, identity);
        byte[] hash = new byte[32]; new SecureRandom().nextBytes(hash);
        Instant expiry = Instant.now().plusSeconds(3600);
        jdbc.update("INSERT INTO xl_auth_session(subject_type,subject_id,refresh_hash,expires_at) VALUES ('user',?,?,?)", userId, hash, java.sql.Timestamp.from(expiry));
        long sid = jdbc.queryForObject("SELECT id FROM xl_auth_session WHERE refresh_hash=?", Long.class, hash);
        return tokens.issue("user", userId, sid, expiry);
    }

    static byte[] picture(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("xinkelink-media-it-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @AfterAll
    static void removeTestImages() throws Exception {
        Path resolved = DIRECTORY.toAbsolutePath().normalize();
        assertTrue(resolved.getFileName().toString().startsWith("xinkelink-media-it-"));
        try (var paths = Files.walk(resolved)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Adapters {
        @Bean @Primary AuthRateLimiter mediaLimiter() { return (operation, key) -> {}; }
    }
}
