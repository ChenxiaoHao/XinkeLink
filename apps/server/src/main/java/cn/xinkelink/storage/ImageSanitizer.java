package cn.xinkelink.storage;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;

/** 两种存储共用原有校验与重编码规则，防止云存储绕过图片安全检查。 */
final class ImageSanitizer {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private ImageSanitizer() {}

    static byte[] sanitize(ImageUpload upload) throws IOException {
        byte[] original = bounded(upload.content());
        String format = signature(original);
        BufferedImage decoded;
        try (ImageInputStream input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> available = ImageIO.getImageReadersByFormatName(format);
            if (!available.hasNext()) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            ImageReader reader = available.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 40_000_000)
                    throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height)
                    throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            } catch (IOException | IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            } finally { reader.dispose(); }
        }
        // 仅保留解码后的像素，不复制EXIF、定位信息或GIF附加帧。
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        LimitedOutput output = new LimitedOutput(encoded);
        try {
            if (!ImageIO.write(decoded, "png", output)) throw new IOException("PNG encoder unavailable");
        } catch (IOException | RuntimeException exception) {
            if (output.exceeded) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
            throw exception;
        }
        return encoded.toByteArray();
    }

    static byte[] bounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        return bytes;
    }

    private static String signature(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return "png";
        if (bytes.length >= 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255) return "jpeg";
        if (bytes.length >= 6 && (new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF87a")
                || new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a"))) return "gif";
        throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    private static final class LimitedOutput extends OutputStream {
        private final OutputStream delegate;
        private int written;
        private boolean exceeded;
        LimitedOutput(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int value) throws IOException { check(1); delegate.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { check(length); delegate.write(bytes, offset, length); }
        private void check(int amount) {
            if ((long) written + amount > MAX_BYTES) {
                exceeded = true;
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
            }
            written += amount;
        }
    }
}
