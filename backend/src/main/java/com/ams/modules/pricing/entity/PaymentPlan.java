package com.ams.modules.pricing.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment_plan")
public class PaymentPlan extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;
    private Integer periodNo;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal plannedAmount;
    private LocalDate dueDate;
    private String status; // pending/issued/voided
}
