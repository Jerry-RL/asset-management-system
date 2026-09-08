package com.ams.modules.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("lease_bundle")
public class LeaseBundle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bundleNo;
    /** combo / split */
    private String bundleType;
    /** package / apportion / area_ratio / independent */
    private String rentMode;
    private BigDecimal totalRent;
    private Long masterContractId;
    private String status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
