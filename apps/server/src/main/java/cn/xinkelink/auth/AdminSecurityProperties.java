package cn.xinkelink.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

@ConfigurationProperties("xinkelink.auth.admin")
public record AdminSecurityProperties(List<String> allowedOrigins) {
    public AdminSecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath() != null && !uri.getPath().isEmpty()
                    || !uri.getScheme().equals("https") && !(uri.getScheme().equals("http")
                    && List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()))) {
                throw new IllegalArgumentException("管理员可信来源配置无效");
            }
        }
    }
}
