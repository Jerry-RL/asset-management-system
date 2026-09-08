package com.ams.modules.meter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("utility_bill")
public class UtilityBill {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long billId;
    private Long contractId;
    private Long meterId;
    private BigDecimal usage;
    private BigDecimal unitPrice;
    private BigDecimal apportionAmount;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
