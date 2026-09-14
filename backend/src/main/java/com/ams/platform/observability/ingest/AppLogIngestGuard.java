package com.ams.platform.observability.ingest;

import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.AppLogLevel;
import com.ams.platform.observability.AppLogSource;
import com.ams.platform.observability.AppType;
import com.ams.platform.observability.dto.AppLogIngestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 上报内容的<strong>解析后</strong>校验与截断（设计 §7.2）。
 *
 * <p>纯逻辑、无副作用（除读时钟），因此可以脱离 Spring 上下文做单测。
 *
 * <p>核心原则：<strong>宁可存一条字段被截断的日志，也不要因为某个字段不合法而丢弃整条</strong>
 * —— 日志的价值在于事后能查到，能查到一部分远好于什么都查不到。只有「无法归属到任何端」
 * 这类无法补救的情况才拒绝（返回 400 让 SDK 侧暴露问题）。
 */
@Component
public class AppLogIngestGuard {

    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_URL_LENGTH = 512;
    public static final int MAX_UA_LENGTH = 512;
    /** extra 序列化后的上限；超出整块丢弃（半个 JSON 无法解析，留着只会误导）。 */
    public static final int MAX_EXTRA_BYTES = 16 * 1024;

    /** traceId 允许的字符与长度：与后端生成的 32 位 hex 兼容，也容忍端侧带连字符的形式。 */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    /** 超过该偏差视为端侧时钟不可信，改用服务端时间并标记。 */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofHours(24);

    private final ObjectMapper objectMapper;

    public AppLogIngestGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 校验失败的响应（由 Controller 转成 400）。 */
    public static class RejectedException extends RuntimeException {
        public RejectedException(String message) {
            super(message);
        }
    }

    /**
     * 校验并归一化为可落库的实体。
     *
     * @param request     上报请求体
     * @param clientIp    服务端取到的来源 IP（<strong>不取请求体</strong>，避免伪造）
     * @param occurredNow 服务端当前时间（注入以便测试时钟偏差分支）
     * @throws RejectedException 无法归属到任何端/来源/级别时
     */
    public AppLog normalize(AppLogIngestRequest request, String clientIp, LocalDateTime occurredNow) {
        AppType appType = AppType.of(request.getAppType())
                .filter(type -> type != AppType.BACKEND)
                .orElseThrow(() -> new RejectedException("appType 非法"));
        AppLogSource source = AppLogSource.of(request.getSource())
                .orElseThrow(() -> new RejectedException("source 非法"));
        AppLogLevel level = AppLogLevel.of(request.getLevel())
                .orElseThrow(() -> new RejectedException("level 非法"));

        AppLog log = new AppLog();
        log.setAppType(appType.code());
        log.setSource(source.code());
        log.setLevel(level.name());
        log.setTraceId(normalizeTraceId(request.getTraceId()));
        log.setMessage(truncate(request.getMessage(), MAX_MESSAGE_LENGTH));
        log.setUrl(truncate(request.getUrl(), MAX_URL_LENGTH));
        log.setUa(truncate(request.getUa(), MAX_UA_LENGTH));
        log.setUserId(request.getUserId());
        log.setClientIp(truncate(clientIp, 64));
        log.setCreatedAt(occurredNow);

        // 端侧时钟不可信：偏差超过阈值就存服务端时间，并把偏差标记在 extra 里，
        // 让排查者知道「这条的 occurredAt 不可用」而不是被一个错的时间误导。
        ClockSkewOutcome skew = resolveOccurredAt(request.getOccurredAt(), occurredNow);
        log.setOccurredAt(skew.occurredAt());
        log.setExtra(serializeExtra(request.getExtra(), skew.skewed()));
        return log;
    }

    private String normalizeTraceId(String raw) {
        if (raw != null && TRACE_ID_PATTERN.matcher(raw).matches()) {
            return raw;
        }
        // 不拒绝：宁可丢掉「端侧与后端的关联」，不可丢掉日志本身
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record ClockSkewOutcome(LocalDateTime occurredAt, boolean skewed) {
    }

    private ClockSkewOutcome resolveOccurredAt(String raw, LocalDateTime occurredNow) {
        if (raw == null || raw.isBlank()) {
            return new ClockSkewOutcome(occurredNow, false);
        }
        try {
            Instant instant = Instant.parse(raw);
            LocalDateTime clientTime =
                    LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            Duration skew = Duration.between(occurredNow, clientTime).abs();
            if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
                return new ClockSkewOutcome(occurredNow, true);
            }
            return new ClockSkewOutcome(clientTime, false);
        } catch (DateTimeParseException ex) {
            return new ClockSkewOutcome(occurredNow, false);
        }
    }

    /**
     * 序列化 extra 并施加大小上限。
     *
     * <p>超限时<strong>整块丢弃</strong>而不是截断字符串：截断后的半个 JSON 无法解析，
     * 展示出来只会误导排查者。改置 {@code {"truncated":true}} 明确表达「这里丢过东西」。
     */
    private String serializeExtra(Object extra, boolean clockSkewed) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (extra instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        } else if (extra != null) {
            merged.put("value", extra);
        }
        if (clockSkewed) {
            merged.put("clockSkew", true);
        }
        if (merged.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(merged);
            if (json.length() > MAX_EXTRA_BYTES) {
                return "{\"truncated\":true}";
            }
            return json;
        } catch (Exception ex) {
            // 端侧 extra 里有无法序列化的结构（循环引用等）—— 不能因此丢掉整条日志
            return "{\"unserializable\":true}";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
