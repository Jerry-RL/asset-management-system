package com.ams.modules.adjustment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("fee_relief_request")
public class FeeReliefRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long billId;
    private Long contractId;
    private Long tenantId;
    private BigDecimal reliefAmount;
    private String reason;
    private String fileIds;
    private Boolean majorFlag;
    /** draft / approving / approved / rejected / applied */
    private String status;
    private String approvalBizType;
    private LocalDateTime appliedAt;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}
