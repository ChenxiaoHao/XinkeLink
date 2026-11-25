package cn.xinkelink.content.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xinkelink.content-security")
public record ContentSecurityProperties(
        boolean wechatEnabled,
        boolean failOpen,
        boolean mediaCheckEnabled,
        String callbackToken,
        String publicBaseUrl) {

    public ContentSecurityProperties(boolean wechatEnabled, boolean failOpen) {
        this(wechatEnabled, failOpen, false, "", "");
    }

    @Override
    public String toString() {
        return "ContentSecurityProperties[wechatEnabled=" + wechatEnabled
                + ", failOpen=" + failOpen
                + ", mediaCheckEnabled=" + mediaCheckEnabled
                + ", callbackToken=[已隐藏], publicBaseUrl=" + publicBaseUrl + "]";
    }
}
