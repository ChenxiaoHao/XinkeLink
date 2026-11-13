package cn.xinkelink.common.web;

import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.common.api.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(BusinessException exception) { return error(exception.code()); }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiResponse<Void>> validation(BindException exception) {
        List<ApiResponse.FieldError> errors = exception.getFieldErrors().stream()
                .map(field -> new ApiResponse.FieldError(field.getField(), "invalid", "字段不符合要求"))
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.failure(ErrorCode.VALIDATION_FAILED, errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ApiResponse<Void>> invalid(Exception exception) { return error(ErrorCode.VALIDATION_FAILED); }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiResponse<Void>> missing(Exception exception) { return error(ErrorCode.NOT_FOUND); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> method(Exception exception) { return error(ErrorCode.METHOD_NOT_ALLOWED); }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> media(Exception exception) { return error(ErrorCode.UNSUPPORTED_MEDIA_TYPE); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> uploadSize(Exception exception) { return error(ErrorCode.FILE_TOO_LARGE); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        // 只记录关联标识和异常类别，不拼接请求、凭据、SQL或原始异常正文。
        log.error("请求处理异常，requestId={}，type={}", RequestIdFilter.currentId(), exception.getClass().getSimpleName());
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> error(ErrorCode code) {
        // 图片等二进制接口失败时，仍按统一 JSON 错误结构返回。
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.failure(code));
    }
}
