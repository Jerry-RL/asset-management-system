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
    private Long tenantId;
    private Long contractId;
    private BigDecimal leaseArea;
    private BigDecimal rentAmount;
    private BigDecimal areaRatio;
    private LocalDateTime createdAt;
}
