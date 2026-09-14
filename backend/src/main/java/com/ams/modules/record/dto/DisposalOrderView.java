package com.ams.modules.record.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 资产处置单的回显形状 —— 设计 §7.1。
 *
 * <p>与 {@link DisposalInput} 分开而不是合并：{@code disposal_order} 带状态机
 * （{@code draft/approving/.../completed}）与损益字段，把它压成台账形状会让前端
 * 误以为项目/分区也能走审批。前端按 {@code status} 与权限码显隐操作按钮。
 *
 * <p><strong>但字段名与台账对齐</strong>（{@code disposalType / disposalUserId / disposalUserName /
 * amountWan / disposalDate / remark / attachments}）：三处的处置面板共用同一个卡片组件，
 * 只按 {@code ownerType} 决定「能不能编辑、有没有状态与流转按钮」，见设计 §6.6。
 * 需要额外注意的是 {@link #amountWan} 的**单位与台账不同**（见其 javadoc）。
 */
@Data
public class DisposalOrderView {

    private Long id;

    private String disposalType;

    private Long disposalUserId;

    private String disposalUserName;

    /**
     * 处置金额。
     *
     * <p>字段名沿用台账的 {@code amountWan}（前端只认这一个键），但**单位不是万元**：
     * 这里直接是 {@code disposal_order.actual_amount} 的原值，与台账的
     * {@code biz_disposal_record.amount_wan} 之间**不做任何换算**（设计 §4.2 末尾）。
     * 前端在卡片的输入框上用 {@code addonAfter} 区分单位，避免同一个标签误导录入。
     */
    private BigDecimal amountWan;

    private LocalDate disposalDate;

    private String remark;

    /** draft / approving / rejected / pending_execute / executing / completed。 */
    private String status;

    // ---- 以下四个字段只有资产侧有（台账没有这些业务概念），卡片在 ownerType=asset 时渲染 ----

    private String reason;

    private BigDecimal assessedValue;

    private BigDecimal bookValue;

    private String counterparty;

    private List<AttachmentRef> attachments = new ArrayList<>();
}
