package cn.xinkelink.common.api;

// 对外提示来自受控错误码，不能直接使用数据库或第三方异常正文。
public final class BusinessException extends RuntimeException {
    private final ErrorCode code;
    public BusinessException(ErrorCode code) { super(code.message()); this.code = code; }
    public ErrorCode code() { return code; }
}
