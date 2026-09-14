package com.ams.modules.system.dto;

import java.time.LocalDateTime;

/**
 * 操作日志的查询视图（FR-COM-004）。
 *
 * <p>{@code detail} 是 <b>解析后的对象</b>（非库里的 JSON 字符串）：查询页要直接渲染它，
 * 把解析放在服务端可以避免前端对非法 JSON 做二次容错。
 *
 * <p>{@code refId} 是「被操作对象的 id」，由切面推断（规则见 {@code AuditRefIdResolver}）。
 * 它可能为 {@code null}：批量操作（导入、合并、清理）没有「那个对象」可言，
 * 推断不出时切面刻意留空而不是猜一个。
 */
public record OperationLogView(
        Long id,
        Long userId,
        String username,
        String module,
        String action,
        Long refId,
        Object detail,
        String ip,
        String traceId,
        LocalDateTime createdAt,
        /** {@code true} 成功 / {@code false} 失败 / {@code null} = V51 之前的存量行（未知）。 */
        Boolean success,
        String error) {
}
