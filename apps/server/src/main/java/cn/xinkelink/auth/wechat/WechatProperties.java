package cn.xinkelink.auth.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xinkelink.auth.wechat")
public record WechatProperties(String appId, String appSecret) {
    // record 默认诊断文本会包含所有字段，密钥必须主动隐藏。
    @Override
    public String toString() {
        return "WechatProperties[appId=" + appId + ", appSecret=[已隐藏]]";
    }
}
