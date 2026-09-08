package com.ams.modules.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("invoice_reconcile")
public class InvoiceReconcile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long invoiceId;
    private Long paymentId;
    private Long billId;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
