package cn.xinkelink.content.security;

/**
 * 微信内容安全检测场景枚举（对应 wxa/msg_sec_check v2 scene 参数）。
 */
public enum ContentScene {
    PROFILE(1),
    COMMENT(2),
    FORUM(3),
    MESSAGE(4);

    private final int code;

    ContentScene(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
