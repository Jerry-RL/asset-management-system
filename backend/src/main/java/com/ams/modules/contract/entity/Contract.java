package com.ams.modules.contract.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract")
public class Contract extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String contractNo;
    private Long assetId;
    private Long tenantId;
    @Version
    private Integer version;
    private Long parentContractId;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal leaseArea;
    private String rentType; // fixed_monthly/fixed_yearly/per_area/per_unit/negotiable
    private BigDecimal rentAmount;
    private BigDecimal depositAmount;
    private BigDecimal prepayAmount;
    private String paymentCycle; // monthly/quarterly/yearly
    private Integer freeRentDays;
    private BigDecimal increaseRate;
    private String increasePeriod;
    private Integer graceDays;
    private String prorationBase; // calendar / fixed_30
    private String contractType;
    private String status; // 合同状态机九态
    private String esignStatus;
    private String paymentStatus;
    private String remark;
}
