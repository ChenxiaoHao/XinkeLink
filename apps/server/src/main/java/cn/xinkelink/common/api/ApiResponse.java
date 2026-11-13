package cn.xinkelink.common.api;

import cn.xinkelink.common.web.RequestIdFilter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record ApiResponse<T>(String code, String message, T data,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> errors,
        String requestId, Instant timestamp) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "成功", data, List.of(), RequestIdFilter.currentId(), Instant.now());
    }

    public static ApiResponse<Void> failure(ErrorCode code) {
        return failure(code, List.of());
    }

    public static ApiResponse<Void> failure(ErrorCode code, List<FieldError> errors) {
        return new ApiResponse<>(code.name(), code.message(), null, List.copyOf(errors),
                RequestIdFilter.currentId(), Instant.now());
    }

    public record FieldError(String field, String code, String message) {}
}
