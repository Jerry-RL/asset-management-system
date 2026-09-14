package com.ams.platform.observability.webhook;

import java.time.LocalDateTime;

/**
 * 告警的结构化字段。
 *
 * <p>{@code generic} 型 Webhook 直接序列化本对象；{@code wecom} / {@code dingtalk} 只把
 * {@code title} 与渲染好的 markdown 正文发出去（两家的 markdown 语法不通用，正文由
 * {@link WebhookMessageFormatter} 分别渲染）。
 */
public record WebhookPayload(
        String level,
        String message,
        String traceId,
        String appType,
        String source,
        String url,
        String fingerprint,
        int count,
        LocalDateTime time) {
}
