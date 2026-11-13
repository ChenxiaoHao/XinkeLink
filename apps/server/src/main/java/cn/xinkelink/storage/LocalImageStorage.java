package cn.xinkelink.storage;

import cn.xinkelink.media.MediaContentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.UUID;

/** 仅开发期使用，目录不会映射为HTTP静态资源。 */
@Component
@Profile("auth")
@ConditionalOnProperty(name = "xinkelink.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalImageStorage implements ImageStorage, MediaContentReader {
    public static final int MAX_BYTES = ImageSanitizer.MAX_BYTES;
    private final Path root;

    public LocalImageStorage(@Value("${XINKELINK_IMAGE_DIRECTORY:.local/backend-images}") String directory) {
        Path configured = Path.of(directory);
        Path project = Path.of("").toAbsolutePath().normalize();
        // 从apps/server启动时仍将默认文件写入项目忽略目录。
        if (!configured.isAbsolute() && project.endsWith(Path.of("apps", "server"))) project = project.getParent().getParent();
        this.root = project.resolve(configured).toAbsolutePath().normalize();
    }

    @Override
    public StoredImage store(ImageUpload upload) throws IOException {
        byte[] safe = ImageSanitizer.sanitize(upload);
        Files.createDirectories(root);
        String key = UUID.randomUUID().toString() + ".png";
        Path target = path(key);
        try { Files.write(target, safe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
        catch (IOException exception) { Files.deleteIfExists(target); throw exception; }
        // 厂商边界保留URI字段；本地适配器只返回不含路径的opaque URI，业务不对外使用。
        return new StoredImage(key, URI.create("urn:xinkelink:image:" + key), "image/png", safe.length);
    }

    @Override public byte[] read(String key) throws IOException {
        try (InputStream input = Files.newInputStream(path(key))) { return ImageSanitizer.bounded(input); }
    }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }

    private Path path(String key) throws IOException {
        if (key == null || !key.matches("[a-f0-9-]{36}\\.png")) throw new IOException("Invalid image key");
        Path resolved = root.resolve(key).normalize();
        if (!resolved.getParent().equals(root) || Files.isSymbolicLink(resolved)) throw new IOException("Invalid image key");
        return resolved;
    }

}
