package com.ams.platform.observability.dto;

import java.util.List;

/**
 * 查询页顶部统计条（近 24h）。
 *
 * @param windowHours    统计窗口小时数（固定 24，下发以便前端展示不硬编码）
 * @param total          窗口内总数
 * @param byLevel        各 level 计数
 * @param byAppType      各端计数
 * @param topFingerprint 出现最多的错误指纹（按次数降序，最多 10 条）
 */
public record AppLogStats(
        int windowHours,
        long total,
        List<CountItem> byLevel,
        List<CountItem> byAppType,
        List<TopError> topFingerprint) {

    /** 通用「键 → 计数」。 */
    public record CountItem(String key, long count) {
    }

    /** Top 错误：指纹 + 出现次数 + 一条代表性 message（便于人眼判断）。 */
    public record TopError(String fingerprint, long count, String level, String appType, String message) {
    }
}
