package cn.xinkelink.auth.wechat;

// 不携带 session_key、unionid 或客户端提供的身份字段。
public record WechatIdentity(String appId, String openId) {}
