package com.ams.modules.billing.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment")
public class Payment extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paymentNo;
    private Long contractId;
    private Long tenantId;
    private BigDecimal amount;
    private String method;            // cash/bank_transfer/wechat/alipay
    private String channel;           // user_mp/worker_mp/pc
    private String confirmStatus;     // pending/confirmed
    private LocalDateTime confirmedAt;
    private Long confirmedBy;
    private LocalDateTime paidAt;
    private String bankReconcileStatus;
    private String remark;
    private String source;
}
