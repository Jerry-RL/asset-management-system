package com.ams.modules.asset.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 计租单元拆分请求（ADR-0021 决策 B / 规则 S1–S12）。
 *
 * <p>{@code childAreas} 是子单元面积列表：至少 2 个、每个大于 0、合计须等于原单元面积
 * （容差 0.005）。业务侧不需要传"拆成几个"，列表长度即个数。
 *
 * @param childAreas 子单元面积（顺序即单元的 sort 顺序）
 * @param remark     操作备注，写入结构变更日志与软删单元备注
 */
public record AssetUnitSplitRequest(List<BigDecimal> childAreas, String remark) {
}
