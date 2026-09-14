package com.ams.modules.mortgage.dto;

import com.ams.modules.record.dto.AttachmentRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/**
 * 抵押记录表单入参（新建 / 编辑草稿共用）。
 *
 * <p><b>没有 {@code status} 字段</b>：状态只能由服务端状态机推进。让表单能写 {@code status}，
 * 就等于允许把一条已解押的记录改回 {@code active} —— 那笔抵押会瞬间"复活"，
 * 并且开始拦截处置 / 流转，而任何界面都看不出它是被谁改回来的。
 *
 * <p><b>没有 {@code endDate} 字段</b>：到期日由 {@code startDate + termMonths} 推导。
 * 表单只给期限（月），让用户另填一个到期日就会出现两个互相矛盾的数字，
 * 而到期预警读的是 {@code end_date} —— 用户按还款日填、预警按期限算，两边永远对不上。
 */
@Data
public class MortgageRecordInput {

    /** 编辑草稿时由路径上的 id 提供；新建时忽略。 */
    private Long id;

    /** 所属公司。标的必须归属该公司。 */
    private Long companyId;

    /** 标的类型：{@code project} / {@code zone} / {@code asset}。 */
    private String targetType;

    /** 标的 id。一条记录一个标的。 */
    private Long targetId;

    /** 抵押权人（抵押公司 / 人）。 */
    private String mortgagee;

    private BigDecimal amount;

    /** 利率，百分数（{@code 4.35} 表示 4.35%）。 */
    private BigDecimal interestRate;

    private String bank;

    /** 还款日（业务到期日）。 */
    private LocalDate repaymentDate;

    /** 抵押起始时间。与 {@code termMonths} 一起推导到期日。 */
    private LocalDate startDate;

    /** 抵押期限（月）。 */
    private Integer termMonths;

    private String contractNo;

    private List<AttachmentRef> attachments;
}
