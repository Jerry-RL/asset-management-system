package com.ams.modules.fixedasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("fa_inventory_item")
public class FaInventoryItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;
    private Long fixedAssetId;
    private String bookStatus;
    private String actualStatus;
    private String remark;
    private LocalDateTime scannedAt;
    private LocalDateTime createdAt;
}
