package cn.xinkelink.auth.wechat;

// 微信换码边界只返回身份，平台会话由上层服务创建。
public interface WechatCodeClient {
    WechatIdentity exchange(String code);
}
