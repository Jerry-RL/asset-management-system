package com.ams.modules.transferrecord.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资产调拨记录明细（{@code asset_transfer_record_asset}，V55 迁移）：一张单 × 一个资产。
 *
 * <p>{@code fromDepartmentId} / {@code fromUserId} 是**原值快照**：生效那一刻该资产的责任
 * 部门与责任人。没有这两列的话，生效后 asset 上已经是新值，档案里就只能看到「调拨过」而
 * 看不出「从哪交接来的」。
 *
 * <p>附件挂在**主单**上（{@code AttachmentOwner.ASSET_TRANSFER_RECORD}），明细行不带
 * {@code deleted_at}：明细是主单的全量替换（先删后插），留软删只会制造永不清理的孤儿行。
 * 全量替换的另一面是每次编辑都会换新 id，所以明细行的 id **不能**用作对外引用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_transfer_record_asset")
public class AssetTransferRecordAsset extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long recordId;

    private Long assetId;

    /** 生效时的原责任部门（快照）。 */
    private Long fromDepartmentId;

    /** 生效时的原责任人（快照）。 */
    private Long fromUserId;
}
