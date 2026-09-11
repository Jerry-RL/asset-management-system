package com.ams.modules.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("lease_bundle_item")
public class LeaseBundleItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bundleId;
    private Long assetId;
    /** 计租单元（asset_unit.id）；bundle 明细是「分配视图」，单元才是真源（ADR-0019） */
    private Long assetUnitId;
    private Long tenantId;
    private Long contractId;
    private BigDecimal leaseArea;
    private BigDecimal rentAmount;
    private BigDecimal areaRatio;
    private LocalDateTime createdAt;
}
