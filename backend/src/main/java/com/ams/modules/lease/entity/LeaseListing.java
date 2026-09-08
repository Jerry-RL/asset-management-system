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
    private BigDecimal rentAmount;
    private Boolean rentNegotiable;
    private String status; // active / closed
    private LocalDateTime publishedAt;
    private LocalDateTime closedAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
