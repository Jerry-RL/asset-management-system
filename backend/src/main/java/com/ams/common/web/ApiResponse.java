package com.ams.common.web;

import com.ams.common.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应结构（对齐 docs/api/openapi.yaml — code 为整数）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return ApiResponse.<T>builder()
                .code(ErrorCode.OK.getCode())
                .message(ErrorCode.OK.getMessage())
                .data(data)
                .traceId(traceId)
                .build();
    }

    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String traceId) {
        return ApiResponse.<T>builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .traceId(traceId)
                .build();
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, String traceId) {
        return ApiResponse.<T>builder()
                .code(errorCode.getCode())
                .message(message == null ? errorCode.getMessage() : message)
                .traceId(traceId)
                .build();
    }
}
