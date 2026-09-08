package com.ams.modules.adjustment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("rent_adjust_request")
public class RentAdjustRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;
    private BigDecimal oldRentAmount;
    private BigDecimal newRentAmount;
    private LocalDate effectiveDate;
    /** keep / diff_bill */
    private String issuedStrategy;
    private String reason;
    private String fileIds;
    /** draft / approving / approved / rejected / applied */
    private String status;
    private LocalDateTime appliedAt;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}
