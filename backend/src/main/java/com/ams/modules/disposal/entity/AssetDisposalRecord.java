package com.ams.modules.disposal.entity;

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
 * 资产处置记录（{@code asset_disposal_record}，V57）—— 一行 = 一个**被处置的资产**。
 *
 * <p>与既有两张表的分工：
 * <ul>
 *   <li>{@code disposal_order}：资产级处置单，带审批状态机。完成时**级联**写本表；</li>
 *   <li>{@code biz_disposal_record}：项目 / 分区级处置台账（记录即处置）。新增一行时
 *       **级联**展开为「其下每个资产一行」写本表；</li>
 *   <li>本表：只读的「被处置资产」台账，供「资产处置记录」菜单展示，也是
 *       「资产已脱离原产权公司」这件事的**唯一可追溯凭据**（产权公司已被清空）。</li>
 * </ul>
 *
 * <p><b>为什么快照而不是 JOIN 来源单据</b>：{@code biz_disposal_record} 走 record-sheet
 * 全量 diff、可以被软删；{@code disposal_order} 也可能被改名。处置不可逆，台账必须自洽 ——
 * 否则删一张来源单据就会让已发生的处置在界面上变成空白。
 *
 * <p>{@link #amountUnit} 与 {@link #disposalAmount} 成对：资产级是元、项目/分区级是万元，
 * 既有口径**不做换算**，用单位列显式标注，避免同一个金额列被当成两种量纲读。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_disposal_record")
public class AssetDisposalRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;

    /** 处置对象层级：asset / project / zone。 */
    private String targetType;

    /** 处置对象 id：asset.id / project.id / project_zone.id。 */
    private Long targetId;

    /** 资产级来源：{@code disposal_order.id}；项目 / 分区级为 null。 */
    private Long sourceOrderId;

    /** 项目 / 分区级来源：{@code biz_disposal_record.id}；资产级为 null。 */
    private Long sourceRecordId;

    /** 处置时资产的原产权公司（快照，随后被清空）。 */
    private Long fromPropertyCompanyId;

    /** 处置时资产的原经营公司（快照）。 */
    private Long fromOperatingCompanyId;

    private String disposalType;

    /** 处置金额，单位见 {@link #amountUnit}。 */
    private BigDecimal disposalAmount;

    /** 金额单位：yuan 元 / wan 万元。 */
    private String amountUnit;

    private LocalDate disposalDate;

    private Long disposalUserId;

    private String disposalUserName;

    private String remark;

    /** 级联处置时间（被处置资产进入终态的那一刻）。 */
    private LocalDateTime disposedAt;
}
