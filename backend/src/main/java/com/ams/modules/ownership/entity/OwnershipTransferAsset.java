package com.ams.modules.ownership.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权属流转明细：一张单 × 一个资产（{@code ownership_transfer_asset}，V54 迁移）。
 *
 * <p>两个 {@code from*} 是**生效时的原值快照**，不是「读 asset 现值」——
 * 生效后资产已经改成新公司，资产再被流转一次或公司改名，靠现值反推就追溯不到了。
 *
 * <p>没有 {@code deletedAt}：草稿改明细走全量 diff 真删（同 {@code DisposalService.syncForAsset}
 * 的做法）。明细行不被任何外部对象引用（附件挂在主单上），留软删只会制造永不清理的孤儿行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ownership_transfer_asset")
public class OwnershipTransferAsset extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long transferId;

    private Long assetId;

    /** 生效时该资产的产权公司原值。 */
    private Long fromPropertyCompanyId;

    /** 生效时该资产的经营公司原值。 */
    private Long fromOperatingCompanyId;
}
