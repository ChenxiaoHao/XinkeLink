package cn.xinkelink.storage;

import cn.xinkelink.media.MediaContentReader;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

/** 必须显式运行的真实云端检查，只清理本次新建的对象，不列举或删除已有数据。 */
public final class OssStorageLiveCheck {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("XINKELINK_OSS_LIVE_TEST"))) throw new IllegalStateException("尚未显式启用真实OSS检查");
        // 独立诊断程序没有Boot日志配置，只保留本工具的安全结果输出。
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.aliyun.oss"))
                .setLevel(ch.qos.logback.classic.Level.OFF);
        String bucket = value("XINKELINK_OSS_BUCKET"), region = value("XINKELINK_OSS_REGION"), endpoint = value("XINKELINK_OSS_ENDPOINT");
        var config = new OssStorageConfiguration();
        var oss = config.ossClient(endpoint, region, value("XINKELINK_OSS_ACCESS_KEY_ID"), value("XINKELINK_OSS_ACCESS_KEY_SECRET"));
        var directory = Files.createTempDirectory("xinkelink-oss-check-");
        var storage = new OssImageStorage(oss, bucket, directory.toString());
        StoredImage saved = null;
        try {
            byte[] pixels = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a06kAAAAASUVORK5CYII=");
            saved = storage.store(new ImageUpload("oss-connectivity.png", "image/png", pixels.length, new ByteArrayInputStream(pixels)));
            byte[] read = ((MediaContentReader) storage).read(saved.key());
            if (read.length != saved.size()) throw new IllegalStateException("OSS读取内容长度不一致");
            if (!Arrays.equals(read, ImageSanitizer.sanitize(new ImageUpload("check.png", "image/png", pixels.length, new ByteArrayInputStream(pixels)))))
                throw new IllegalStateException("OSS回读校验不一致");
            System.out.println("真实OSS上传、读取与像素校验：通过");
            URI publicUrl = URI.create("https://" + bucket + ".oss-" + region + ".aliyuncs.com/" + saved.key());
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(HttpRequest.newBuilder(publicUrl).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 403) throw new IllegalStateException("匿名访问未按预期被拒绝，状态码：" + response.statusCode());
            System.out.println("私有桶匿名读取：403，通过");
        } catch (Exception exception) {
            // 不打印供应商完整异常、请求头或凭据。
            System.out.println("真实OSS检查失败：" + exception.getClass().getSimpleName());
            if (exception instanceof java.io.IOException) System.out.println(exception.getMessage());
            throw new IllegalStateException("OSS连接或权限验证失败，请检查专用RAM策略及网络");
        } finally {
            try {
                if (saved != null) {
                    storage.delete(saved.key());
                    if (oss.doesObjectExist(bucket, saved.key())) throw new IllegalStateException("本次测试对象仍存在");
                    System.out.println("本次测试对象清理及不存在校验：通过");
                }
            } finally { oss.shutdown(); Files.deleteIfExists(directory); }
        }
    }
    private static String value(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + "未配置");
        return value;
    }
}
