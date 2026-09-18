package com.ams.modules.assetoperator.dto;

import lombok.Data;

/**
 * 运营范围下拉候选（V60）：项目 / 分区 / 资产共用同一个形态。
 *
 * <p>三类标的用同一个 DTO，是为了让前端的范围行只有一种渲染分支（`mortgageTargetLabel`
 * 那套拼接）：{@code parentName} 为空时只显示 {@code name}，不为空时显示
 * {@code parentName / name} —— 项目是顶层，它的上级就是自己（取 null）。
 *
 * <p>候选按 {@code scopeType} 分派查询，**必须**带公司条件：项目有 {@code company_id}，
 * 分区与资产经 {@code project.company_id} / {@code asset.asset_company_id} 收敛
 * （与 V56 抵押标的候选同一口径，避免列出别的公司的标的）。
 */
@Data
public class AssetOperatorScopeOption {

    private Long scopeId;

    /** project / zone / asset。 */
    private String scopeType;

    /** 标的名：项目名 / 分区名 / 资产名。 */
    private String name;

    /** 上级名：分区与资产返回所属项目名；项目为 null。 */
    private String parentName;

    /** 资产编号（仅资产标的有值），列表上用来区分同名资产。 */
    private String assetNo;
}
