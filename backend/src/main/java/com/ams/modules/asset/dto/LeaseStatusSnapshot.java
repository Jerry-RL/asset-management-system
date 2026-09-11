package com.ams.modules.asset.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 派生租控状态快照（{@code v_asset_lease_status_derived} 的投影）。
 *
 * <p>派生逻辑只存在于 SQL 视图一处，Java 侧仅做投影与回写，
 * 避免「Java 与 SQL 两套派生规则漂移」——这是 B1 决策（保留 9 值物化派生）能长期成立的关键。
 */
@Data
public class LeaseStatusSnapshot {

    private Long assetId;
    private String assetNo;
    /** 物化列当前值（asset.lease_control_status） */
    private String storedStatus;
    /** 由占用集合派生的目标值 */
    private String derivedStatus;
    /** in_book / exited */
    private String lifecycleStatus;
    /** 占用率（%） */
    private BigDecimal occupancyRatio;
}
