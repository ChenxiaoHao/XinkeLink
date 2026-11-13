package cn.xinkelink.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "登录凭据已过期，请刷新或重新登录"),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "登录会话已失效，请重新登录"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码不正确"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有执行此操作的权限"),
    ACCOUNT_BANNED(HttpStatus.FORBIDDEN, "账号当前无法使用此功能"),
    ACTION_RESTRICTED(HttpStatus.FORBIDDEN, "当前账号没有此操作权限"),
    WECHAT_LOGIN_FAILED(HttpStatus.BAD_GATEWAY, "微信登录暂时不可用，请重新获取登录凭据后重试"),
    WECHAT_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "微信登录尚未配置，请联系管理员"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务暂时不可用，请稍后重试"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试"),
    BETA_ACCESS_REQUIRED(HttpStatus.FORBIDDEN, "请先激活内测资格"),
    BETA_CODE_UNAVAILABLE(HttpStatus.BAD_REQUEST, "内测码不可用，请检查后重试"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求格式不支持"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "记录已更新，请刷新后重试"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "当前状态不允许此操作"),
    STATE_UNCHANGED(HttpStatus.CONFLICT, "操作未改变当前状态"),
    INVALID_RANGE(HttpStatus.BAD_REQUEST, "请求范围不合法"),
    MESSAGE_BLOCKED(HttpStatus.FORBIDDEN, "当前无法向该会话发送消息"),
    REPORT_PENDING(HttpStatus.CONFLICT, "该内容已有待处理举报"),
    ALREADY_PROCESSED(HttpStatus.CONFLICT, "举报已经处理，请刷新查看"),
    AUDIENCE_CHANGED(HttpStatus.CONFLICT, "接收范围已变化，请重新预览"),
    NO_RECIPIENTS(HttpStatus.UNPROCESSABLE_ENTITY, "当前没有符合条件的接收用户"),
    AUDIENCE_TOO_LARGE(HttpStatus.UNPROCESSABLE_ENTITY, "接收人数超过本次同步发送上限"),
    CURSOR_INVALID(HttpStatus.BAD_REQUEST, "分页游标无效，请刷新列表"),
    TARGET_UNAVAILABLE(HttpStatus.NOT_FOUND, "内容暂不可用"),
    DUPLICATE_REQUEST_CONFLICT(HttpStatus.CONFLICT, "同一请求标识的内容不一致"),
    REPLY_TARGET_INVALID(HttpStatus.CONFLICT, "回复目标已失效"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "请提供记录版本"),
    IMAGE_FORBIDDEN(HttpStatus.FORBIDDEN, "无权使用此图片"),
    IMAGE_IN_USE(HttpStatus.CONFLICT, "图片已被内容引用"),
    UNSUPPORTED_TARGET(HttpStatus.BAD_REQUEST, "暂不支持此用途"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "图片大小超过限制"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "图片存储暂不可用"),
    EXPORT_TOO_LARGE(HttpStatus.UNPROCESSABLE_ENTITY, "导出结果超过10000条，请缩小筛选范围"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用，请稍后重试");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    public HttpStatus status() { return status; }
    public String message() { return message; }
}
