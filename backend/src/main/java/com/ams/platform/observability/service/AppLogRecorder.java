package com.ams.platform.observability.service;

import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.AppLogSource;
import com.ams.platform.observability.AppType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 把后端未捕获异常写入 {@code app_log}（设计 §9）。
 *
 * <p>存在的意义：端侧报错与后端异常<strong>共用同一个 trace_id</strong>，于是
 * {@code GET /system/app-logs/trace/{traceId}} 能一次性看到「前端报了什么 + 后端出了什么」，
 * 这就是「全链路」在排查时的实际价值。
 *
 * <p><strong>不记 {@code AppException}（业务 4xx）</strong>：那是预期内的用户错误
 * （参数不对、状态冲突），记进来只会淹没有价值的信号，让 ERROR 告警失真。
 */
@Component
public class AppLogRecorder {

    /** 入库的堆栈只保留前若干行：完整堆栈在日志文件里，库里的用途是「快速看一眼」。 */
    private static final int STACK_HEAD_LINES = 30;

    private final AppLogService appLogService;
    private final ObjectMapper objectMapper;

    public AppLogRecorder(AppLogService appLogService, ObjectMapper objectMapper) {
        this.appLogService = appLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一次未捕获的后端异常。
     *
     * <p><strong>调用位置与事务的重要说明</strong>：本方法由
     * {@code GlobalExceptionHandler} 调用，此时业务事务早已回滚完毕，请求线程上没有活动事务，
     * 因此普通的 insert 会自动提交 —— <strong>不要加 {@code REQUIRES_NEW}</strong>。
     * 加了只会额外占用一个数据库连接（挂起一个空事务再开新事务），没有任何收益；
     * 这条结论是刻意写在这里的，否则后人看到「异常处理里要写库」会顺手补上传播级别。
     *
     * @param ex      未捕获异常
     * @param traceId 本次请求的 traceId（与响应体、响应头、日志文件里的完全一致）
     */
    public void recordBackendException(Throwable ex, String traceId) {
        try {
            AppLog log = new AppLog();
            log.setTraceId(traceId);
            log.setLevel("ERROR");
            log.setAppType(AppType.BACKEND.code());
            log.setSource(AppLogSource.BACKEND.code());
            log.setMessage(describe(ex));
            log.setExtra(buildExtra(ex));
            log.setOccurredAt(LocalDateTime.now());
            log.setClientIp(currentClientIp());
            log.setUrl(currentRequestUrl());
            // 指纹由 AppLogService 统一计算（第二个参数提供堆栈入参）
            appLogService.record(log, ex == null ? null : stackTrace(ex));
        } catch (Exception ignored) {
            // 双重保险：AppLogService 自己已经吞异常了，这里再兜一层。
            // 日志系统故障绝不能让业务响应变成另一个错误。
        }
    }

    private static String describe(Throwable ex) {
        if (ex == null) {
            return "未知异常";
        }
        String message = ex.getMessage();
        String simpleName = ex.getClass().getSimpleName();
        return message == null || message.isBlank() ? simpleName : simpleName + ": " + message;
    }

    private String buildExtra(Throwable ex) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (ex != null) {
            extra.put("exception", ex.getClass().getName());
            extra.put("stack", headLines(stackTrace(ex), STACK_HEAD_LINES));
        }
        ServletRequestAttributes attrs = currentRequestAttributes();
        if (attrs != null) {
            extra.put("method", attrs.getRequest().getMethod());
            extra.put("path", attrs.getRequest().getRequestURI());
        }
        try {
            return objectMapper.writeValueAsString(extra);
        } catch (Exception ignored) {
            return "{\"unserializable\":true}";
        }
    }

    private static String stackTrace(Throwable ex) {
        StringWriter writer = new StringWriter();
        ex.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String headLines(String value, int maxLines) {
        if (value == null) {
            return null;
        }
        String[] lines = value.split("\n");
        if (lines.length <= maxLines) {
            return value;
        }
        return String.join("\n", java.util.Arrays.copyOf(lines, maxLines));
    }

    private static ServletRequestAttributes currentRequestAttributes() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra : null;
    }

    private static String currentClientIp() {
        ServletRequestAttributes attrs = currentRequestAttributes();
        return attrs == null ? null : attrs.getRequest().getRemoteAddr();
    }

    private static String currentRequestUrl() {
        ServletRequestAttributes attrs = currentRequestAttributes();
        return attrs == null ? null : attrs.getRequest().getRequestURI();
    }
}
