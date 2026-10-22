package cn.xinkelink.storage;

import java.io.InputStream;

/**
 * Original filename and contentType are untrusted metadata.
 * size is the declared byte length; implementations must enforce actual limits.
 */
public record ImageUpload(
        String originalFilename,
        String contentType,
        long size,
        InputStream content
) {}
