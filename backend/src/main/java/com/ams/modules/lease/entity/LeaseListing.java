package com.ams.modules.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("lease_listing")
public class LeaseListing {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    /** 招租标的单元（asset_unit.id）；是「意向」而非占用，不参与区间互斥（ADR-0019） */
    private Long assetUnitId;
    private BigDecimal rentAmount;
    private Boolean rentNegotiable;
    private String status; // active / closed
    private LocalDateTime publishedAt;
    private LocalDateTime closedAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
