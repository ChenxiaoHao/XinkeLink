package cn.xinkelink.content.security;

import java.util.Optional;

public interface WechatContentSecurityClient {
    /**
     * 调用微信文本安全检测（msg_sec_check v2）。
     * 内容不通过时抛出 BusinessException(ErrorCode.CONTENT_REJECTED)。
     */
    void checkText(String openId, ContentScene scene, String content);

    /**
     * 发起微信图片异步安全检测（media_check_async v2）。
     * 返回微信侧 trace_id，供异步回调匹配具体图片。
     */
    default Optional<String> checkImageAsync(String openId, ContentScene scene, String mediaUrl) {
        return Optional.empty();
    }
}
