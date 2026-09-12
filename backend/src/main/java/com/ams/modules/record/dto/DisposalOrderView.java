package com.ams.modules.record.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 资产处置单的回显形状（只读）—— 设计 §7.1。
 *
 * <p>与 {@link DisposalInput} 分开而不是合并：{@code disposal_order} 带状态机
 * （{@code draft/approving/.../completed}）与损益字段，把它压成台账形状会让前端
 * 误以为项目/分区也能走审批。前端按 {@code status} 与权限码显隐操作按钮。
 */
@Data
public class DisposalOrderView {

    private Long id;

    private String disposalType;

    private Long disposalUserId;

    private String disposalUserName;

    /** 处置金额（沿用 disposal_order.actual_amount 的原值，**不做万元换算**）。 */
    private BigDecimal amountWan;

    private LocalDate disposalDate;

    private String remark;

    /** draft / approving / rejected / pending_execute / executing / completed。 */
    private String status;

    private BigDecimal actualAmount;

    private List<AttachmentRef> attachments = new ArrayList<>();
}
