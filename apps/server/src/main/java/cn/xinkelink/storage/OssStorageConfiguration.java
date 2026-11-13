package cn.xinkelink.storage;

import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@Profile("auth")
@ConditionalOnProperty(name = "xinkelink.storage.provider", havingValue = "oss")
public class OssStorageConfiguration {
    @Bean(destroyMethod = "shutdown")
    OSS ossClient(@Value("${XINKELINK_OSS_ENDPOINT}") String endpoint,
            @Value("${XINKELINK_OSS_REGION}") String region,
            @Value("${XINKELINK_OSS_ACCESS_KEY_ID}") String accessKeyId,
            @Value("${XINKELINK_OSS_ACCESS_KEY_SECRET}") String accessKeySecret) {
        URI uri = URI.create(endpoint);
        if (!region.matches("[a-z0-9-]+") || !"https".equals(uri.getScheme())
                || !("oss-" + region + ".aliyuncs.com").equals(uri.getHost()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() != -1
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new IllegalArgumentException("OSS地域与HTTPS Endpoint不匹配");
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) throw new IllegalArgumentException("OSS凭据未配置");
        var config = new ClientConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(5000); config.setSocketTimeout(10000);
        config.setConnectionRequestTimeout(5000); config.setMaxErrorRetry(0); config.setMaxConnections(20);
        // 使用环境配置，不把凭据放进URL、异常信息或日志。
        return OSSClientBuilder.create().endpoint(endpoint).region(region).clientConfiguration(config)
                .credentialsProvider(new DefaultCredentialProvider(accessKeyId, accessKeySecret)).build();
    }
}
