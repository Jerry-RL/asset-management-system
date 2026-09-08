package com.ams.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("asset_certificate")
public class AssetCertificate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private String certType;
    private String certNo;
    private String ownerName;
    private java.time.LocalDate registerDate;
    private String mortgageStatus;
    private Long fileId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
