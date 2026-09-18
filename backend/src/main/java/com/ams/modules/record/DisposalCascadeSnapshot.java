package com.ams.modules.record;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 级联处置时从来源单据带到处置台账的信息快照（{@link DisposalCascadePort} 的入参）。
 *
 * <p>来源有两处，字段名与**单位**各不相同（资产级 {@code disposal_order.actual_amount} 是元、
 * 项目 / 分区级 {@code biz_disposal_record.amount_wan} 是万元），因此在这里显式归一，
 * 不让两处调用点各自拼参数 —— 那正是「同一个语义两处判定漂移」的温床。
 *
 * @param disposalType     处置方式（字典 {@code disposal_type}）
 * @param disposalAmount   处置金额，单位见 {@code amountUnit}（**不做换算**）
 * @param amountUnit       金额单位：{@link #UNIT_YUAN} 元 / {@link #UNIT_WAN} 万元
 * @param disposalDate     处置日期
 * @param disposalUserId   处置人 id（内员；外部人员为 null）
 * @param disposalUserName 处置人姓名快照
 * @param remark           备注 / 事由
 */
public record DisposalCascadeSnapshot(
        String disposalType,
        BigDecimal disposalAmount,
        String amountUnit,
        LocalDate disposalDate,
        Long disposalUserId,
        String disposalUserName,
        String remark) {

    /** 资产级处置单的金额单位：{@code disposal_order.actual_amount} 是元。 */
    public static final String UNIT_YUAN = "yuan";

    /** 项目 / 分区级处置台账的金额单位：{@code biz_disposal_record.amount_wan} 是万元。 */
    public static final String UNIT_WAN = "wan";
}
