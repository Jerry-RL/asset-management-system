package com.ams.platform.observability.dto;

import java.time.LocalDateTime;

/**
 * 应用日志的查询视图。
 *
 * <p>{@code extra} 在这里是<strong>解析后的对象</strong>（非库里的 JSON 字符串）：
 * 查询页要直接渲染 JSON，把解析放在服务端可以避免前端对非法 JSON 做二次容错。
 */
public record AppLogView(
        Long id,
        String traceId,
        String level,
        String appType,
        String source,
        String fingerprint,
        String message,
        Object extra,
        String ua,
        String url,
        Long userId,
        String clientIp,
        LocalDateTime occurredAt,
        LocalDateTime createdAt) {
}
