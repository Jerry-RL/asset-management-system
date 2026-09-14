package com.ams.modules.record.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 成本信息（{@code biz_cost_record}）—— 后续记录扩展，1:N，三种主体共用。
 *
 * <p>{@link #amountWan} 单位为**万元**，与处置台账 {@code biz_disposal_record.amount_wan}
 * 口径一致；费用明细另见 {@link CostItem}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_cost_record")
public class CostRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private BigDecimal amountWan;
    private LocalDate costDate;
    private String remark;
    private LocalDateTime deletedAt;
}
