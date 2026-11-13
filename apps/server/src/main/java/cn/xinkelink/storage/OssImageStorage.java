package cn.xinkelink.storage;

import cn.xinkelink.media.MediaContentReader;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/** 新图片写入私有桶；旧的不透明键仍读取本地，不自动迁移或降级云端请求。 */
@Component
@Profile("auth")
@ConditionalOnProperty(name = "xinkelink.storage.provider", havingValue = "oss")
public class OssImageStorage implements ImageStorage, MediaContentReader {
    private static final Logger LOG = LoggerFactory.getLogger(OssImageStorage.class);
    private final OSS oss;
    private final String bucket;
    private final LocalImageStorage legacy;

    public OssImageStorage(OSS oss, @Value("${XINKELINK_OSS_BUCKET}") String bucket,
            @Value("${XINKELINK_IMAGE_DIRECTORY:.local/backend-images}") String directory) {
        if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) throw new IllegalArgumentException("OSS Bucket名称不正确");
        this.oss = oss; this.bucket = bucket; this.legacy = new LocalImageStorage(directory);
    }

    @Override public StoredImage store(ImageUpload upload) throws IOException {
        byte[] safe = ImageSanitizer.sanitize(upload);
        String id = UUID.randomUUID().toString(), key = "oss/" + id + ".png";
        var metadata = new ObjectMetadata();
        metadata.setContentType("image/png"); metadata.setContentLength(safe.length);
        metadata.setHeader("x-oss-forbid-overwrite", "true");
        var request = new PutObjectRequest(bucket, key, new ByteArrayInputStream(safe));
        request.setMetadata(metadata);
        try { oss.putObject(request); }
        catch (OSSException | ClientException exception) { throw failure("上传", exception); }
        return new StoredImage(key, URI.create("urn:xinkelink:image:" + id), "image/png", safe.length);
    }

    @Override public byte[] read(String key) throws IOException {
        if (!cloudKey(key)) return legacy.read(key);
        try (var object = oss.getObject(bucket, key)) { return ImageSanitizer.bounded(object.getObjectContent()); }
        catch (OSSException | ClientException exception) { throw failure("读取", exception); }
    }

    @Override public void delete(String key) throws IOException {
        if (!cloudKey(key)) { legacy.delete(key); return; }
        try { oss.deleteObject(bucket, key); }
        catch (OSSException | ClientException exception) { throw failure("删除", exception); }
    }

    private static IOException failure(String operation, RuntimeException exception) {
        String code = exception instanceof OSSException error ? error.getErrorCode() : "NETWORK_ERROR";
        if (code == null || !code.matches("[A-Za-z0-9_.-]{1,64}")) code = "OSS_ERROR";
        // 只保留错误码，不传播可能包含请求头或凭据的供应商完整异常。
        LOG.warn("OSS图片{}失败，错误码：{}", operation, code);
        return new IOException("OSS图片" + operation + "失败：" + code);
    }

    private static boolean cloudKey(String key) throws IOException {
        if (key == null) throw new IOException("图片存储键无效");
        if (!key.startsWith("oss/")) return false;
        if (!key.matches("oss/[a-f0-9-]{36}\\.png")) throw new IOException("图片存储键无效");
        return true;
    }
}
