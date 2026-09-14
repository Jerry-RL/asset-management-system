package com.ams.platform.observability;

import java.util.Optional;

/**
 * 日志级别（封闭词表，与 {@code app_log} 的 {@code ck_app_log_level} 约束一致）。
 *
 * <p>本期端侧只产生 {@link #ERROR}；{@code WARN} / {@code INFO} 为二期（业务埋点）预留。
 */
public enum AppLogLevel {

    ERROR,
    WARN,
    INFO;

    /** 解析级别；缺省为 {@link #ERROR}（端侧上报默认就是错误）。 */
    public static Optional<AppLogLevel> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.of(ERROR);
        }
        for (AppLogLevel level : values()) {
            if (level.name().equalsIgnoreCase(code.trim())) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
