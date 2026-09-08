package com.ams.modules.disposal.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("disposal_order")
public class DisposalOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private String disposalType; // sale/scrap/transfer
    private String reason;
    private BigDecimal assessedValue;
    private BigDecimal bookValue;
    private BigDecimal actualAmount;
    private String counterparty;
    private String status; // draft/approving/rejected/pending_execute/executing/completed
    /** 处置损益金额（实际 − 账面/评估基准）。 */
    private BigDecimal pnlAmount;
    /** gain / loss */
    private String pnlType;
    private Long paymentId;
    private Long voucherId;
}
