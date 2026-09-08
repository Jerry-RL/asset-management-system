package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset")
public class Asset extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String assetNo;
    private String name;
    private String assetType; // property / land
    private BigDecimal area;
    private String sourceType;
    private String ownershipType;
    private Long propertyCompanyId;
    private Long operatingCompanyId;
    private String leaseControlStatus;
    private BigDecimal baseRentAssessed;
    private BigDecimal baseRentFloor;
    private BigDecimal marketRefRent;
    private String province;
    private String city;
    private String district;
    private String address;
    private String structureType;
    private String usageType;
    private String waterMeterNo;
    private String electricMeterNo;
    private BigDecimal originalValue;
    private String qrCodeUrl;
    private Long parentAssetId;
    private String vacantReason;
    private java.time.LocalDateTime vacantSince;
    private BigDecimal longitude;
    private BigDecimal latitude;
    /** active / frozen / merged_out */
    private String structureStatus;
    private Long rootAssetId;
    private String oldAssetNo;

    @Version
    private Integer version;
}
