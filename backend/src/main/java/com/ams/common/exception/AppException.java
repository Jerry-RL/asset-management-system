package com.ams.common.exception;

import lombok.Getter;

/**
 * 业务异常，携带统一错误码与可选的 HTTP 状态。
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final int httpStatus;

    public AppException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.httpStatus = defaultHttpStatus(errorCode);
    }

    public AppException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = defaultHttpStatus(errorCode);
    }

    public AppException(ErrorCode errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    private static int defaultHttpStatus(ErrorCode code) {
        int c = code.getCode();
        if (c == 0) {
            return 200;
        }
        if (c < 50000) {
            return switch (c) {
                case 40100, 40101 -> 401;
                case 40300, 40301 -> 403;
                case 40400 -> 404;
                case 40900, 40901, 42201, 42202, 42203 -> 409;
                case 42900 -> 429;
                default -> 400;
            };
        }
        if (c == 50001 || c == 50300) {
            return 503;
        }
        return 500;
    }
}
