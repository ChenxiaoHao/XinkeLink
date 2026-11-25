package cn.xinkelink.content.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xinkelink.content-security")
public record ContentSecurityProperties(boolean wechatEnabled, boolean failOpen) {
}
