package com.ams.modules.audit.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_audit_item")
public class AssetAuditItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;
    private Long assetId;
    private String expectedStatus;
    private String actualStatus;
    private String varianceType; // none/surplus/deficit/status_mismatch
    private String scanCode;
    private LocalDateTime scannedAt;
    private Long scannedBy;
    private String status; // pending/scanned/variance/approved/rejected
    private String remark;
}
