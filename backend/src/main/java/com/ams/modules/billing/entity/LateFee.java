package com.ams.modules.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("late_fee")
public class LateFee {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long billId;
    private BigDecimal rateSnapshot;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal feeAmount;
    private String status; // accruing/waived/settled
    private LocalDateTime createdAt;
}
