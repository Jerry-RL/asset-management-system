package com.ams.modules.invoice.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("invoice")
public class Invoice extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String invoiceNo;
    private Long paymentId;
    private Long billId;
    private Long titleId;
    private BigDecimal amount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private String status; // pending_issue/issuing/issued/red_flushing/red_flushed/failed
    private String thirdPartyNo;
    private Long redFlushRefId;
}
