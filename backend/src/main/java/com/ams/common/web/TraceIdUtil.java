package com.ams.common.web;

/**
 * TraceId 上下文工具（配合 TraceIdFilter 与 MDC 使用）。
 */
public final class TraceIdUtil {

    private TraceIdUtil() {
    }

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    public static void set(String traceId) {
        if (traceId == null) {
            TRACE_ID.remove();
        } else {
            TRACE_ID.set(traceId);
        }
    }

    public static String get() {
        return TRACE_ID.get();
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
