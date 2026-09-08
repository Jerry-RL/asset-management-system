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
@TableName("refund_order")
public class RefundOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paymentId;
    private BigDecimal amount;
    private String reason;
    private String channel;
    private String thirdPartyRefundNo;
    private String status; // applying/approving/executing/completed/rejected
}
