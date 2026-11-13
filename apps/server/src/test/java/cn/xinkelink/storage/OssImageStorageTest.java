package cn.xinkelink.storage;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.media.MediaContentReader;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OssImageStorageTest {
    @TempDir Path directory;
    private final OSS oss = mock(OSS.class);
    private ImageStorage storage() {
        Class<?> type = assertDoesNotThrow(() -> Class.forName("cn.xinkelink.storage.OssImageStorage"), "必须实现真实OSS存储适配器");
        return (ImageStorage) assertDoesNotThrow(() -> type.getConstructor(OSS.class, String.class, String.class)
                .newInstance(oss, "example-dev-bucket", directory.toString()));
    }
    private static byte[] picture() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }
    private static ImageUpload upload(byte[] bytes) {
        return new ImageUpload("../../不可信名称.jpg", "text/plain", 1, new ByteArrayInputStream(bytes));
    }

    @Test void uploadsOnlyVerifiedPixelsWithDistinctProviderKeysAndNoOverwrite() throws Exception {
        var storage = storage();
        byte[] image = picture(), marker = "private-EXIF-location".getBytes(StandardCharsets.UTF_8);
        byte[] original = Arrays.copyOf(image, image.length + marker.length);
        System.arraycopy(marker, 0, original, image.length, marker.length);
        StoredImage saved = storage.store(upload(original));
        var capture = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(oss).putObject(capture.capture());
        PutObjectRequest request = capture.getValue();
        byte[] pixels = request.getInputStream().readAllBytes();
        assertEquals("example-dev-bucket", request.getBucketName());
        assertTrue(saved.key().matches("oss/[a-f0-9-]{36}\\.png"));
        assertEquals(saved.key(), request.getKey());
        assertEquals("image/png", request.getMetadata().getContentType());
        assertEquals("true", request.getMetadata().getRawMetadata().get("x-oss-forbid-overwrite"));
        assertEquals(saved.size(), pixels.length);
        assertFalse(new String(pixels, StandardCharsets.ISO_8859_1).contains("private-EXIF"));
        assertEquals(4, ImageIO.read(new ByteArrayInputStream(pixels)).getWidth());
    }

    @Test void refusesInvalidImagesBeforeCallingOss() {
        var storage = storage();
        assertThrows(BusinessException.class, () -> storage.store(upload("fake image".getBytes(StandardCharsets.UTF_8))));
        verifyNoInteractions(oss);
    }

    @Test void readsOssObjectAndClosesTheResponse() throws Exception {
        var storage = storage();
        String key = "oss/" + java.util.UUID.randomUUID() + ".png";
        var content = spy(new ByteArrayInputStream(picture()));
        var object = new OSSObject(); object.setObjectContent(content);
        when(oss.getObject("example-dev-bucket", key)).thenReturn(object);
        assertArrayEquals(picture(), ((MediaContentReader) storage).read(key));
        verify(content).close();
    }

    @Test void oldLocalPicturesRemainReadableWithoutOssCalls() throws Exception {
        var old = new LocalImageStorage(directory.toString());
        StoredImage saved = old.store(upload(picture()));
        assertArrayEquals(old.read(saved.key()), ((MediaContentReader) storage()).read(saved.key()));
        verifyNoInteractions(oss);
        old.delete(saved.key());
    }

    @Test void cloudFailureNeverFallsBackToLocalAndDoesNotLeakProviderMessages() {
        var storage = storage();
        String key = "oss/" + java.util.UUID.randomUUID() + ".png";
        when(oss.getObject("example-dev-bucket", key)).thenThrow(new OSSException("secret-provider-diagnostics"));
        IOException error = assertThrows(IOException.class, () -> ((MediaContentReader) storage).read(key));
        assertFalse(error.toString().contains("secret-provider"));
        assertNull(error.getCause());
    }

    @Test void rejectsPathsAndMalformedCloudKeysBeforeAnyRemoteOperation() {
        var storage = storage();
        for (String key : new String[]{"../secret", "oss/../../secret", "oss/invalid.png", "https://example.invalid/file"}) {
            assertThrows(IOException.class, () -> ((MediaContentReader) storage).read(key));
            assertThrows(IOException.class, () -> storage.delete(key));
        }
        verifyNoInteractions(oss);
    }

    @Test void deletesOnlyTheRequestedCloudObject() throws Exception {
        var storage = storage(); String key = "oss/" + java.util.UUID.randomUUID() + ".png";
        storage.delete(key); verify(oss).deleteObject("example-dev-bucket", key); verifyNoMoreInteractions(oss);
    }

    @Test void refusesOversizedRemoteResponseAndClosesItsStream() throws Exception {
        var storage = storage(); String key = "oss/" + java.util.UUID.randomUUID() + ".png";
        var content = spy(new ByteArrayInputStream(new byte[LocalImageStorage.MAX_BYTES + 1]));
        var object = new OSSObject(); object.setObjectContent(content);
        when(oss.getObject("example-dev-bucket", key)).thenReturn(object);
        assertThrows(BusinessException.class, () -> ((MediaContentReader) storage).read(key));
        verify(content).close();
    }
}
