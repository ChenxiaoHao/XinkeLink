package cn.xinkelink.media;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.storage.ImageUpload;
import cn.xinkelink.storage.LocalImageStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class LocalImageStorageTest {
    @TempDir Path directory;

    @Test
    void decodesJpegPngGifAndReencodesPixelsWithoutOriginalMetadata() throws Exception {
        var storage = new LocalImageStorage(directory.toString());
        for (String format : new String[]{"jpeg", "png", "gif"}) {
            byte[] pixels = MediaHttpIT.picture(format, 4, 3);
            byte[] marker = "source-private-location-EXIF".getBytes();
            byte[] original = Arrays.copyOf(pixels, pixels.length + marker.length);
            System.arraycopy(marker, 0, original, pixels.length, marker.length);
            var result = storage.store(new ImageUpload("../../secret.jpg", "text/plain", 1, new ByteArrayInputStream(original)));
            byte[] safe = storage.read(result.key());
            assertEquals("image/png", result.contentType());
            assertEquals((byte)137, safe[0]);
            assertFalse(new String(safe, java.nio.charset.StandardCharsets.ISO_8859_1).contains("source-private-location"));
            assertEquals(4, javax.imageio.ImageIO.read(new ByteArrayInputStream(safe)).getWidth());
            storage.delete(result.key());
        }
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void enforcesActualStreamLimitEvenWhenDeclaredSizeLies() throws Exception {
        var storage = new LocalImageStorage(directory.toString());
        InputStream generated = new InputStream() {
            int remaining = 10 * 1024 * 1024 + 1;
            @Override public int read() { return remaining-- > 0 ? 0 : -1; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = Math.min(length, remaining); Arrays.fill(bytes, offset, offset + count, (byte)0); remaining -= count; return count;
            }
        };
        assertEquals(ErrorCode.FILE_TOO_LARGE, assertThrows(BusinessException.class,
                () -> storage.store(new ImageUpload("ok.png", "image/png", 0, generated))).code());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void rejectsExcessiveDimensionsPixelBombTruncationAndWebpBeforeStoring() throws Exception {
        var storage = new LocalImageStorage(directory.toString());
        byte[] valid = MediaHttpIT.picture("png", 1, 1);
        for (int[] dimensions : new int[][]{{8193, 1}, {8000, 5001}}) {
            byte[] excessive = valid.clone();
            ByteBuffer.wrap(excessive).putInt(16, dimensions[0]).putInt(20, dimensions[1]);
            CRC32 crc = new CRC32(); crc.update(excessive, 12, 17); ByteBuffer.wrap(excessive).putInt(29, (int)crc.getValue());
            assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE, assertThrows(BusinessException.class,
                    () -> storage.store(new ImageUpload("bomb.png", "image/png", excessive.length, new ByteArrayInputStream(excessive)))).code());
        }
        for (byte[] invalid : new byte[][]{Arrays.copyOf(valid, 12), "RIFF1234WEBPVP8 ".getBytes()}) {
            assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE, assertThrows(BusinessException.class,
                    () -> storage.store(new ImageUpload("ok.png", "image/png", invalid.length, new ByteArrayInputStream(invalid)))).code());
        }
        assertThrows(IOException.class, () -> storage.read("../../secret"));
        assertThrows(IOException.class, () -> storage.delete("../../secret"));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void rejectsImageWhenSafeReencodingWouldExceedDatabaseByteLimit() throws Exception {
        var pixels = new java.awt.image.BufferedImage(2500, 2500, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var noise = new java.util.Random(381);
        int[] row = new int[2500];
        for (int y = 0; y < 2500; y++) {
            for (int x = 0; x < row.length; x++) row[x] = noise.nextInt(0x1000000);
            pixels.setRGB(0, y, 2500, 1, row, 0, 2500);
        }
        var original = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels, "jpeg", original);
        assertTrue(original.size() < 10 * 1024 * 1024);
        var storage = new LocalImageStorage(directory.toString());
        assertEquals(ErrorCode.FILE_TOO_LARGE, assertThrows(BusinessException.class,
                () -> storage.store(new ImageUpload("photo.jpg", "image/jpeg", original.size(), new ByteArrayInputStream(original.toByteArray())))).code());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
