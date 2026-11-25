package cn.xinkelink.media;

import cn.xinkelink.auth.AuthPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.media.persistence.MediaMapper;
import cn.xinkelink.storage.ImageStorage;
import cn.xinkelink.storage.ImageUpload;
import cn.xinkelink.storage.StoredImage;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.xinkelink.media.persistence.MediaMapper.*;

@Service
@Profile("auth")
public class MediaService {
    private final MediaMapper mapper;
    private final ImageStorage storage;
    private final MediaContentReader reader;
    private final LoadingCache<String, byte[]> avatarBytes;
    private final MediaGrant grants;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public MediaService(MediaMapper mapper, ImageStorage storage, MediaContentReader reader, MediaGrant grants,
                        Clock clock, PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.storage = storage; this.reader = reader; this.grants = grants;
        this.clock = clock; this.transactions = new TransactionTemplate(transactionManager);
        this.avatarBytes = Caffeine.newBuilder()
                .maximumWeight(16 * 1024 * 1024)
                .weigher((String key, byte[] bytes) -> Math.max(bytes.length, 1024))
                .expireAfterAccess(Duration.ofMinutes(10))
                .build(key -> {
                    try { return reader.read(key); }
                    catch (IOException exception) { throw new UncheckedIOException(exception); }
                });
    }

    public Map<String, Object> upload(AuthPrincipal owner, String purpose, ImageUpload upload) {
        if(!supported(purpose)&&!"report".equals(purpose)&&!"avatar".equals(purpose))throw new BusinessException(ErrorCode.UNSUPPORTED_TARGET);
        requireSession(owner);
        StoredImage stored = null;
        try {
            // 文件处理在写入事务之外；调用方提供的文件名、MIME和声明大小均不参与信任判定。
            stored = storage.store(upload);
            byte[] safe = reader.read(stored.key());
            int[] dimensions = dimensions(safe);
            StoredImage result = stored;
            Asset asset = transactions.execute(status -> {
                requireSession(owner);
                mapper.insert(owner.subjectId(), purpose, result.key(), result.size(), dimensions[0], dimensions[1],
                        sha256(safe), clock.instant().plusSeconds(86400));
                return mapper.byKey(result.key());
            });
            return meta(Objects.requireNonNull(asset));
        } catch (IOException exception) {
            cleanup(stored); throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        } catch (RuntimeException exception) { cleanup(stored); throw exception; }
    }

    public Map<String, Object> access(long imageId, AuthPrincipal viewer) {
        if(viewer!=null&&"admin".equals(viewer.actorType())){
            requireAdminSession(viewer);
            Asset asset=usable(mapper.find(imageId));
            for(Ref ref:mapper.refs(imageId)){
                if(reportType(ref.ownerType())&&mapper.reportExists(ref.ownerId())==1)
                    return access(asset,new Parent(ref.ownerId(),0,false,false),ref.ownerType(),viewer);
                if(supported(ref.ownerType())||"user_avatar".equals(ref.ownerType())){
                    Parent parent=parents(ref.ownerType(),List.of(ref.ownerId())).get(ref.ownerId());
                    if(parent!=null&&!parent.deleted())return access(asset,parent,"admin_"+ref.ownerType(),viewer);
                }
            }
        }
        viewer = publicViewer(viewer);
        Asset asset = usable(mapper.find(imageId));
        for (Ref ref : mapper.refs(imageId)) {
            if (!supported(ref.ownerType())&&!"user_avatar".equals(ref.ownerType())) continue;
            Parent parent = parents(ref.ownerType(), List.of(ref.ownerId())).get(ref.ownerId());
            if (canRead(parent, viewer)) return access(asset, parent, ref.ownerType(), viewer);
        }
        // 已经绑定过但已解绑的ready图不再允许owner绕过父内容状态获取访问权。
        if (asset.state().equals("temporary") && viewer != null && asset.ownerUserId() == viewer.subjectId()
                && asset.expiresAt() != null && asset.expiresAt().isAfter(clock.instant()))
            return access(asset, null, "temporary", viewer);
        throw new BusinessException(ErrorCode.IMAGE_FORBIDDEN);
    }

    /** 案件证据只能由有效后台会话查看；原内容隐藏或删除不移除保全引用。 */
    public List<Map<String,Object>> reportImages(long reportId,String refType,AuthPrincipal admin){
        requireAdminSession(admin);
        if(!reportType(refType))throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if(mapper.reportExists(reportId)!=1)throw new BusinessException(ErrorCode.NOT_FOUND);
        List<Map<String,Object>> images=new ArrayList<>();
        for(Ref ref:mapper.refsForParents(refType,List.of(reportId))){
            Asset asset=mapper.find(ref.imageId());
            if(asset!=null&&asset.deletedAt()==null&&"ready".equals(asset.state()))
                images.add(access(asset,new Parent(reportId,0,false,false),refType,admin));
        }
        return images;
    }

    /** 每页统一批量加载引用、图片和父内容，避免每图查询。 */
    public Map<Long, List<Map<String, Object>>> images(String parentType, Collection<Long> parentIds, AuthPrincipal viewer) {
        if(!"user_avatar".equals(parentType))requirePurpose(parentType);
        boolean admin=viewer!=null&&"admin".equals(viewer.actorType());
        if(admin)requireAdminSession(viewer);else viewer = publicViewer(viewer);
        Map<Long, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (parentIds == null || parentIds.isEmpty()) return result;
        Set<Long> ids = new LinkedHashSet<>(parentIds);
        ids.forEach(id -> result.put(id, new ArrayList<>()));
        Map<Long, Parent> parents = parents(parentType, ids);
        List<Ref> refs = mapper.refsForParents(parentType, ids);
        if (refs.isEmpty()) return result;
        Map<Long, Asset> assets = mapper.assets(refs.stream().map(Ref::imageId).distinct().toList()).stream()
                .collect(Collectors.toMap(Asset::id, Function.identity()));
        for (Ref ref : refs) {
            Parent parent = parents.get(ref.ownerId());
            Asset asset = assets.get(ref.imageId());
            if ((admin&&parent!=null&&!parent.deleted()||canRead(parent, viewer)) && asset != null && asset.deletedAt() == null && asset.state().equals("ready"))
                result.get(parent.id()).add(access(asset, parent, admin?"admin_"+parentType:parentType, viewer));
        }
        return result;
    }

    /** 必须加入调用方业务事务；同一个图片只允许绑定同一个业务目标。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(long owner, String purpose, List<String> imageIds, String parentType, long parentId) {
        requirePurpose(purpose); requirePurpose(parentType);
        if (!purpose.equals(parentType) || parentId <= 0 || imageIds == null || imageIds.size() > 9) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        List<Long> ids = imageIds.stream().map(MediaService::id).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        // 与业务发布共用用户锁顺序，再按图片ID递增锁定，防并发跨目标抢占。
        if (mapper.lockOwner(owner) == null) throw new BusinessException(ErrorCode.IMAGE_FORBIDDEN);
        Parent parent = parents(parentType, List.of(parentId)).get(parentId);
        if (parent == null || parent.deleted() || parent.ownerId() != owner) throw new BusinessException(ErrorCode.IMAGE_FORBIDDEN);
        for (long imageId : ids.stream().sorted().toList()) {
            Asset asset = usable(mapper.lock(imageId));
            if (asset.ownerUserId() != owner || !asset.purpose().equals(purpose)) throw new BusinessException(ErrorCode.IMAGE_FORBIDDEN);
            List<Ref> refs = mapper.refs(imageId);
            boolean sameParent = refs.stream().anyMatch(ref -> ref.ownerType().equals(parentType) && ref.ownerId() == parentId);
            boolean otherParent = refs.stream().anyMatch(ref -> supported(ref.ownerType())
                    && (!ref.ownerType().equals(parentType) || ref.ownerId() != parentId));
            if (otherParent || (asset.state().equals("ready") && !sameParent)
                    || (asset.state().equals("temporary") && (asset.expiresAt() == null || !asset.expiresAt().isAfter(clock.instant()))))
                throw new BusinessException(ErrorCode.IMAGE_FORBIDDEN);
        }
        mapper.detach(parentType, parentId);
        for (int position = 0; position < ids.size(); position++) { mapper.ready(ids.get(position)); mapper.ref(parentType, parentId, position, ids.get(position)); }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void detach(String parentType, long parentId) {
        requirePurpose(parentType);
        // 仅移除当前业务引用，举报证据和快照保全引用不受影响。
        mapper.detach(parentType, parentId);
    }

    public byte[] content(long imageId, String token) {
        MediaGrant.Claim claim = grants.verify(token, imageId);
        Asset asset = usable(mapper.find(imageId));
        if(claim.parentType().startsWith("admin_")){
            requireAdminSession(new AuthPrincipal(claim.userId(),claim.sessionId(),"admin"));
            String type=claim.parentType().substring(6);
            if(!supported(type)&&!type.equals("user_avatar"))forbidden();
            Parent parent=parents(type,List.of(claim.parentId())).get(claim.parentId());
            if(parent==null||parent.deleted()||mapper.refs(imageId).stream().noneMatch(ref->ref.ownerType().equals(type)&&ref.ownerId()==claim.parentId()))forbidden();
            return readContent(asset, "user_avatar".equals(type));
        }
        if(reportType(claim.parentType())){
            AuthPrincipal admin=new AuthPrincipal(claim.userId(),claim.sessionId(),"admin");
            requireAdminSession(admin);
            if(mapper.reportExists(claim.parentId())!=1||mapper.refs(imageId).stream().noneMatch(ref->ref.ownerType().equals(claim.parentType())&&ref.ownerId()==claim.parentId()))forbidden();
            return readContent(asset, false);
        }
        AuthPrincipal viewer = claim.userId() == 0 ? null : new AuthPrincipal(claim.userId(), claim.sessionId(), "user");
        if (viewer != null) requireSession(viewer);
        if (claim.parentType().equals("temporary")) {
            if (viewer == null || viewer.subjectId() != asset.ownerUserId() || !asset.state().equals("temporary")
                    || asset.expiresAt() == null || !asset.expiresAt().isAfter(clock.instant())) forbidden();
        } else {
            if(!claim.parentType().equals("user_avatar"))requirePurpose(claim.parentType());
            boolean currentRef = mapper.refs(imageId).stream().anyMatch(ref -> ref.ownerType().equals(claim.parentType()) && ref.ownerId() == claim.parentId());
            Parent parent = parents(claim.parentType(), List.of(claim.parentId())).get(claim.parentId());
            if (!currentRef || !canRead(parent, viewer)) forbidden();
        }
        return readContent(asset, "user_avatar".equals(claim.parentType()));
    }

    private byte[] readContent(Asset asset, boolean cacheAvatar) {
        try {
            // Requests reach this cache only after validating their own grant and current media reference.
            return cacheAvatar ? avatarBytes.get(asset.storageKey()) : reader.read(asset.storageKey());
        } catch (IOException | UncheckedIOException exception) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    public void delete(long imageId, AuthPrincipal owner) {
        requireSession(owner);
        Asset asset = transactions.execute(status -> {
            Asset locked = mapper.lock(imageId);
            if (locked == null || locked.ownerUserId() != owner.subjectId()) throw new BusinessException(ErrorCode.IMAGE_FORBIDDEN);
            if (!mapper.refs(imageId).isEmpty()) throw new BusinessException(ErrorCode.IMAGE_IN_USE);
            if (!locked.state().equals("deleted")) mapper.state(imageId, "deleting");
            return locked;
        });
        try { storage.delete(Objects.requireNonNull(asset).storageKey()); }
        catch (IOException exception) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE); }
        avatarBytes.invalidate(asset.storageKey());
        transactions.executeWithoutResult(status -> mapper.state(imageId, "deleted"));
    }

    private Map<String, Object> access(Asset asset, Parent parent, String type, AuthPrincipal viewer) {
        Instant expiry = Instant.ofEpochSecond(clock.instant().getEpochSecond() + 60);
        boolean publicAccess = parent != null && parent.publicVisible() && !type.startsWith("admin_");
        if (!publicAccess && viewer == null) forbidden();
        long uid = publicAccess ? 0 : viewer.subjectId(), sid = publicAccess ? 0 : viewer.sessionId();
        String token = grants.issue(new MediaGrant.Claim(asset.id(), expiry, type, parent == null ? 0 : parent.id(), uid, sid));
        return Map.of("imageId", Long.toString(asset.id()), "url", "/api/v1/images/" + asset.id() + "/content?grant=" + token,
                "expiresAt", expiry, "width", asset.width(), "height", asset.height());
    }
    private Map<Long, Parent> parents(String type, Collection<Long> ids) {
        return (type.equals("user_avatar")?mapper.avatars(ids):mapper.posts(ids)).stream().collect(Collectors.toMap(Parent::id, Function.identity()));
    }
    public Map<Long,Map<String,Object>> avatars(Collection<Long> userIds) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        AuthPrincipal viewer=authentication!=null&&authentication.getPrincipal() instanceof AuthPrincipal principal?principal:null;
        return avatars(userIds,viewer);
    }
    public Map<Long,Map<String,Object>> avatars(Collection<Long> userIds,AuthPrincipal viewer) {
        Map<Long,Map<String,Object>> result=new HashMap<>();
        images("user_avatar",userIds,viewer).forEach((id,images)->{if(!images.isEmpty())result.put(id,images.getFirst());});
        return result;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void bindAvatar(long owner,Long imageId) {
        if(mapper.lockOwner(owner)==null)forbidden();
        if(imageId!=null){
            Asset asset=usable(mapper.lock(imageId));
            if(asset.ownerUserId()!=owner||!"avatar".equals(asset.purpose())
                    ||asset.state().equals("temporary")&&(asset.expiresAt()==null||!asset.expiresAt().isAfter(clock.instant())))forbidden();
            if(mapper.refs(imageId).stream().anyMatch(ref->!ref.ownerType().equals("user_avatar")||ref.ownerId()!=owner))forbidden();
            mapper.ready(imageId);
        }
        mapper.detach("user_avatar",owner);
        if(imageId!=null)mapper.ref("user_avatar",owner,0,imageId);
    }
    private static boolean canRead(Parent parent, AuthPrincipal viewer) {
        return parent != null && !parent.deleted() && (parent.publicVisible() || (viewer != null && viewer.subjectId() == parent.ownerId()));
    }
    private Asset usable(Asset asset) {
        if (asset == null || asset.deletedAt() != null || (!asset.state().equals("temporary") && !asset.state().equals("ready"))) forbidden();
        return asset;
    }
    private void requireSession(AuthPrincipal principal) {
        if (principal == null || !principal.actorType().equals("user")
                || mapper.validSession(principal.subjectId(), principal.sessionId(), clock.instant()) != 1)
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    private AuthPrincipal publicViewer(AuthPrincipal principal) {
        // 后台身份读取公开内容时只按访客处理，不把相同数值ID误作图片所有者。
        if (principal == null || !"user".equals(principal.actorType())) return null;
        requireSession(principal);
        return principal;
    }
    private void requireAdminSession(AuthPrincipal principal){
        if(principal==null)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if(!"admin".equals(principal.actorType()))throw new BusinessException(ErrorCode.FORBIDDEN);
        if(mapper.validAdminSession(principal.subjectId(),principal.sessionId(),clock.instant())!=1)
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    private static boolean reportType(String type){return "report_evidence".equals(type)||"report_snapshot".equals(type);}
    private static void requirePurpose(String purpose) { if (!supported(purpose)) throw new BusinessException(ErrorCode.UNSUPPORTED_TARGET); }
    private static boolean supported(String type) { return "post".equals(type); }
    private static void forbidden() { throw new BusinessException(ErrorCode.IMAGE_FORBIDDEN); }
    private static long id(String id) {
        try { if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(id); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static int[] dimensions(byte[] safe) throws IOException {
        // 已由存储适配器完整解码验证；这里只读取安全PNG头，避免再次分配整张像素图。
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(safe))) {
            var imageReader = ImageIO.getImageReadersByFormatName("png").next();
            try { imageReader.setInput(input, true, true); return new int[]{imageReader.getWidth(0), imageReader.getHeight(0)}; }
            finally { imageReader.dispose(); }
        }
    }
    private static Map<String, Object> meta(Asset asset) {
        return Map.of("id", Long.toString(asset.id()), "purpose", asset.purpose(), "mediaType", asset.mediaType(), "byteSize", asset.byteSize(),
                "width", asset.width(), "height", asset.height(), "state", asset.state(), "expiresAt", asset.expiresAt());
    }
    private void cleanup(StoredImage stored) {
        if (stored != null) try { storage.delete(stored.key()); } catch (IOException ignored) { /* 不回显存储key，失败文件交后续回收。 */ }
    }
}
