package com.ams.modules.billing.entity;

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
@TableName("bill")
public class Bill extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String billNo;
    private Long contractId;
    private Long planId;
    private Long assetId;
    private Long tenantId;
    private String billType; // rent/utility/other
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate dueDate;
    private BigDecimal amount;         // 应收本金
    private BigDecimal paidAmount;     // 已核销本金
    private BigDecimal lateFeeAmount;  // 累计应计滞纳金
    private BigDecimal lateFeePaidAmount; // 已核销滞纳金
    private String status;             // pending_issue/unpaid/partial_paid/paid/reduced/voided
    private Integer dunningLevel;
    private String source;             // system/migration
}
