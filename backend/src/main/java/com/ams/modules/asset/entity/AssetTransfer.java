package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_transfer")
public class AssetTransfer extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private Long fromCompanyId;
    private Long toCompanyId;
    private String transferType; // with_contract / vacant
    private String status; // draft/approving/approved/completed
    private LocalDate effectiveDate;
    private String reason;
}
