package com.ams.modules.asset.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目管理顶部统计条（FR-AST-001）。
 *
 * <p>统计范围与项目列表同源：同一套关键字 / 公司 / 数据范围条件，避免「统计」与「列表」对不上账。
 * 口径见 {@link com.ams.modules.asset.AssetLeaseGroups}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectStats {

    /** 项目数 */
    private long projectCount;

    /** 资产宗数：这些项目下的资产总数 */
    private long assetCount;

    /** 闲置宗数：空置 + 招租中 */
    private long idleCount;

    /** 盘活宗数：在租 + 部分出租 */
    private long revitalizedCount;

    /**
     * 资产利用率(%)：按宗数计，(资产宗数 - 闲置宗数) / 资产宗数 × 100，保留一位小数。
     * 无资产时为 0。
     */
    private BigDecimal utilizationRate;
}
