package com.ams.modules.assetoperator.dto;

import com.ams.modules.assetoperator.entity.AssetOperatorScope;
import lombok.Data;

/**
 * 运营范围展示项：二元组 + 解析出来的名字。
 *
 * <p>{@link #scopeName} 与 {@link #parentName} 在服务层**批量**回填（而不是前端逐行补名）：
 * 列表页每行都可能挂着多个范围，逐行查就是 N+1。
 *
 * <p>{@link #parentName} 的取值：分区 → 所属项目名；资产 → 所属项目名；项目 → {@code null}
 * （它自己就是顶层）。与抵押记录 `MortgageTargetOption` 的拼接口径一致。
 */
@Data
public class AssetOperatorScopeView {

    /** project / zone / asset。 */
    private String scopeType;

    private Long scopeId;

    /** 标的名（项目名 / 分区名 / 资产名）。 */
    private String scopeName;

    /** 上级名（分区与资产所属项目名；项目为 null）。 */
    private String parentName;

    /** 类型中文标签（项目 / 分区 / 资产），由服务层统一给出，避免前端第二份映射。 */
    public String typeLabel() {
        return switch (scopeType == null ? "" : scopeType) {
            case AssetOperatorScope.TYPE_PROJECT -> "项目";
            case AssetOperatorScope.TYPE_ZONE -> "分区";
            case AssetOperatorScope.TYPE_ASSET -> "资产";
            default -> scopeType;
        };
    }
}
