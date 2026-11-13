package cn.xinkelink.storage;

import java.io.IOException;

/**
 * Image storage boundary. Implementations own key generation and MIME/content
 * verification. The caller owns and closes ImageUpload.content().
 * 本地与OSS适配器共用此边界，访问权限由媒体服务判断。
 */
public interface ImageStorage {
    StoredImage store(ImageUpload image) throws IOException;

    /** Deletes an object by its opaque storage key, never by a user-supplied path. */
    void delete(String key) throws IOException;
}
