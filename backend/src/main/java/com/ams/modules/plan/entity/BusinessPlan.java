package com.ams.modules.plan.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("business_plan")
public class BusinessPlan extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private Long projectId;
    private Integer planYear;
    private Integer planMonth;
    private BigDecimal targetRentalRate;
    private BigDecimal targetCollectionRate;
    private BigDecimal targetIncome;
    private BigDecimal targetVacantArea;
    private Integer version;
    private String status; // active/inactive
}
