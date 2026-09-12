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
 * 处置台账（{@code biz_disposal_record}）—— 设计 §4.2，本期仅项目 / 分区。
 *
 * <p>与资产侧的 {@code disposal_order} 刻意分开：后者带状态机、审批与
 * {@code lifecycle_status=exited} 语义，把项目/分区塞进去会让「处置完成就退出资产」这套
 * 业务规则被套用到没有生命周期的对象上。这里只是登记。
 *
 * <p>{@link #amountWan} 单位为**万元**，与 {@code disposal_order.actual_amount} 不做换算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_disposal_record")
public class DisposalRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerType;
    private Long ownerId;
    private String disposalType;
    private Long disposalUserId;
    private String disposalUserName;
    private BigDecimal amountWan;
    private LocalDate disposalDate;
    private String remark;
    private LocalDateTime deletedAt;
}
