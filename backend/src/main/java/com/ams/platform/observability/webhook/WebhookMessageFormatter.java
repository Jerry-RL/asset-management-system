package com.ams.platform.observability.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把告警渲染成各家机器人要的 JSON（设计 §10.2）。
 *
 * <p>纯函数、可单测。三家格式的差异集中在这里，{@link RestClientWebhookNotifier} 只负责发送。
 *
 * <p>为什么要做三家的适配而不是只做一家：项目里同时存在企微（微信生态重）与钉钉的可能，
 * 而发送地址是运维配置项。适配成本约 30 行，换来「换 IM 不改代码」。
 */
public final class WebhookMessageFormatter {

    private final ObjectMapper objectMapper;

    public WebhookMessageFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 支持的类型；无法识别的类型回退 {@code generic}（绝不因为配错类型就不发告警）。 */
    public static final String TYPE_WECOM = "wecom";
    public static final String TYPE_DINGTALK = "dingtalk";
    public static final String TYPE_GENERIC = "generic";

    public String format(String type, String title, String markdownBody, WebhookPayload payload) {
        return switch (normalizeType(type)) {
            case TYPE_WECOM -> toJson(Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("content", markdownBody)));
            case TYPE_DINGTALK -> toJson(Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("title", title, "text", markdownBody)));
            default -> toJson(genericPayload(payload));
        };
    }

    public static String normalizeType(String type) {
        if (type == null) {
            return TYPE_GENERIC;
        }
        String normalized = type.trim().toLowerCase();
        return switch (normalized) {
            case TYPE_WECOM -> TYPE_WECOM;
            case TYPE_DINGTALK -> TYPE_DINGTALK;
            default -> TYPE_GENERIC;
        };
    }

    /**
     * 渲染 markdown 正文（企微/钉钉共用）。
     *
     * <p>钉钉的 markdown 标题只能单行、且会作为推送卡片标题，因此这里把「标题 + 关键字段」
     * 平铺成行，避免使用表格（两家对表格支持不一致）。
     */
    public static String renderMarkdown(String title, WebhookPayload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(title).append('\n');
        sb.append("> **错误**：").append(oneLine(payload.message())).append('\n');
        sb.append("> **端**：").append(payload.appType())
                .append("　**来源**：").append(payload.source())
                .append("　**次数**：").append(payload.count()).append('\n');
        if (payload.url() != null && !payload.url().isBlank()) {
            sb.append("> **页面**：").append(oneLine(payload.url())).append('\n');
        }
        sb.append("> **TraceId**：").append(payload.traceId()).append('\n');
        sb.append("> **指纹**：").append(payload.fingerprint());
        return sb.toString();
    }

    /** 消息里换行会把钉钉 markdown 的引用块截断，压成单行。 */
    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        String flattened = value.replace('\n', ' ').replace('\r', ' ').trim();
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "…";
    }

    /** generic 型的载荷：字段固定，便于订阅方做程序化处理。 */
    private Map<String, Object> genericPayload(WebhookPayload payload) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event", "app_log_alert");
        map.put("level", payload.level());
        map.put("message", payload.message());
        map.put("traceId", payload.traceId());
        map.put("appType", payload.appType());
        map.put("source", payload.source());
        map.put("url", payload.url());
        map.put("fingerprint", payload.fingerprint());
        map.put("count", payload.count());
        map.put("time", payload.time() == null ? null : payload.time().toString());
        return map;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            // 走到这里说明入参结构有问题；不能因此不发告警，降级成一段纯文本
            return "{\"msgtype\":\"text\",\"text\":{\"content\":\"告警序列化失败\"}}";
        }
    }
}
