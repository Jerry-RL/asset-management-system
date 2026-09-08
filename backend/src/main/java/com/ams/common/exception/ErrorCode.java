package com.ams.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举（对齐 docs/api/README.md §4）。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    OK(0, "success"),

    // 4xx 客户端
    BAD_REQUEST(40000, "请求参数错误"),
    BUSINESS_ERROR(40001, "业务规则校验失败"),
    UNAUTHORIZED(40100, "未登录或 Token 失效"),
    TOKEN_EXPIRED(40101, "Token 已过期"),
    FORBIDDEN(40300, "无权限"),
    DATA_SCOPE_FORBIDDEN(40301, "数据权限不足"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "状态冲突，请刷新后重试"),
    DUPLICATE_SUBMIT(40901, "重复提交"),

    // 42x 业务语义
    PRICE_BELOW_FLOOR(42201, "签约价低于底价"),
    TENANT_BLACKLISTED(42202, "租户在黑名单，禁止签约"),
    REPORT_NOT_VERIFIED(42203, "报告未通过溯源校验"),

    RATE_LIMITED(42900, "请求过于频繁"),

    // 5xx 服务端
    INTERNAL_ERROR(50000, "服务器内部错误"),
    THIRD_PARTY_ERROR(50001, "第三方服务异常"),
    SERVICE_UNAVAILABLE(50300, "服务维护中");

    private final int code;
    private final String message;
}
