package cn.xinkelink.content.security;

public interface WechatContentSecurityClient {
    /**
     * 调用微信文本安全检测（msg_sec_check v2）。
     * 内容不通过时抛出 BusinessException(ErrorCode.CONTENT_REJECTED)。
     */
    void checkText(String openId, ContentScene scene, String content);
}
