package com.ams.platform.observability;

import java.util.Optional;

/**
 * 错误来源（封闭词表，与 {@code app_log} 的 {@code ck_app_log_source} 约束一致）。
 *
 * <p>与 {@link AppLogLevel} 是不同维度：{@code source} 说明<strong>是什么错了</strong>，
 * {@code level} 说明<strong>多严重</strong>。
 */
public enum AppLogSource {

    /** JS 运行时错误（window.onerror / 小程序 App.onError）。 */
    JS("js"),
    /** Promise 未捕获（unhandledrejection / 小程序 onUnhandledRejection）。 */
    PROMISE("promise"),
    /** API 失败（HTTP ≥ 500 / 超时 / 网络异常 / 业务码 ≥ 50000）。 */
    API("api"),
    /** 后端未捕获异常（GlobalExceptionHandler 落库）。 */
    BACKEND("backend");

    private final String code;

    AppLogSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<AppLogSource> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (AppLogSource source : values()) {
            if (source.code.equalsIgnoreCase(code.trim())) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }
}
