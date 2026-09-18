package com.ams.modules.disposal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 「资产处置记录」列表 / 详情的回显形状（V57）。
 *
 * <p>资产编号 / 名称 / 项目 / 分区在**读路径**回填（本表只存 asset_id）——同
 * {@code AssetTransferRecordService} 的做法：资产行不会被物理删除，读时批量取名
 * 比写入时快照更不容易漂移。
 *
 * <p>{@link #disposalAmount} 必须与 {@link #amountUnit} 一起读：资产级是元、项目/分区级是
 * 万元，既有口径不做换算（见 {@code AssetDisposalRecord} 的注释）。
 */
@Data
public class AssetDisposalRecordView {

    private Long id;

    private Long assetId;

    private String assetNo;

    private String assetName;

    private String projectName;

    private String zoneName;

    private Integer floorNo;

    /** 处置对象层级：asset / project / zone。 */
    private String targetType;

    private Long targetId;

    private Long sourceOrderId;

    private Long sourceRecordId;

    private Long fromPropertyCompanyId;

    private String fromPropertyCompanyName;

    private Long fromOperatingCompanyId;

    private String fromOperatingCompanyName;

    private String disposalType;

    private BigDecimal disposalAmount;

    /** yuan 元 / wan 万元。 */
    private String amountUnit;

    private LocalDate disposalDate;

    private Long disposalUserId;

    private String disposalUserName;

    private String remark;

    private LocalDateTime disposedAt;
}
