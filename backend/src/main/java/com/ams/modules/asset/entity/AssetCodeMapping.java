package com.ams.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("asset_code_mapping")
public class AssetCodeMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String oldAssetNo;
    private Long newAssetId;
    private String newAssetNo;
    private String opType;
    private Long structureLogId;
    private LocalDateTime createdAt;
}
