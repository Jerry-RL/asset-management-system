package com.ams.modules.dunning.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/** 催缴队列行：欠费账单 + 当前/建议等级。 */
@Data
@Builder
public class DunningQueueItem {

    private Long billId;
    private String billNo;
    private Long contractId;
    private Long tenantId;
    private Long assetId;
    private LocalDate dueDate;
    private long overdueDays;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal arrears;
    private String status;
    private Integer currentLevel;
    private int suggestedLevel;
    /** overdue / pre_due */
    private String phase;
}
