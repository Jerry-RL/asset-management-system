package com.ams.modules.contract.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vacate_order")
public class VacateOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;
    private String status; // applying/inspecting/settling/completed
    private String reason;
    private LocalDate expectedVacateDate;
    private BigDecimal waterReading;
    private BigDecimal electricReading;
    private String inspectionRemark;
    private Long inspectedBy;
    private LocalDateTime inspectedAt;
    private BigDecimal settlementAmount;
    private BigDecimal damageCompensation;
    private BigDecimal depositRefund;
    private BigDecimal prepayRefund;
    private LocalDateTime settledAt;
}
