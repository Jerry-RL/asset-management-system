package com.ams.modules.contract.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("deposit_transaction")
public class DepositTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;
    private String type; // collect/hold/deduct/refund
    private BigDecimal amount;
    private Long refBillId;
    private Long refPaymentId;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
}
