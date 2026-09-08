package com.ams.modules.billing.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prepay")
public class Prepay extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;
    private Long tenantId;
    private BigDecimal amount;
    private BigDecimal usedAmount;
    private BigDecimal balance;
    private String remark;
}
