package com.ams.modules.asset.dto;

import java.util.List;

/**
 * 计租单元合并请求（ADR-0021 决策 C / 规则 S12）。
 *
 * <p>{@code unitIds} 必须属于<b>同一资产</b>且至少 2 个；服务层会去重，
 * 但重复 ID 说明前端选择状态有问题，故去重后不足 2 个一律拒绝而不是静默降级。
 *
 * @param unitIds 待合并的单元 ID（必须属于同一资产）
 * @param remark  操作备注，写入结构变更日志
 */
public record AssetUnitMergeRequest(List<Long> unitIds, String remark) {
}
