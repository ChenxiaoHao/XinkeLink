package cn.xinkelink.storage;

import java.io.IOException;

/**
 * Image storage boundary. Implementations own key generation and MIME/content
 * verification. The caller owns and closes ImageUpload.content().
 * No storage implementation or upload endpoint is enabled in phase one.
 */
public interface ImageStorage {
    StoredImage store(ImageUpload image) throws IOException;

    /** Deletes an object by its opaque storage key, never by a user-supplied path. */
    void delete(String key) throws IOException;
}
