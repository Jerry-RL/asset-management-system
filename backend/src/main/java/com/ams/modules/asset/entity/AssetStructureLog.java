package com.ams.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("asset_structure_log")
public class AssetStructureLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** split / merge */
    private String opType;
    private String sourceAssetIds;
    private String resultAssetIds;
    private String mappingJson;
    private String remark;
    private Long operatorId;
    private LocalDateTime createdAt;
}
