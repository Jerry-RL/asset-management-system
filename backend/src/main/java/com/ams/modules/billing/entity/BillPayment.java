package com.ams.modules.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("bill_payment")
public class BillPayment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long billId;
    private Long paymentId;
    private String amountType; // principal / late_fee
    private BigDecimal amount;
    private LocalDateTime allocatedAt;
    private Long allocatedBy;
}
