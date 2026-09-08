package com.ams.modules.migration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("migration_import_log")
public class MigrationImportLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;
    private Integer rowNo;
    private String bizType;
    private String result; // success/failed
    private String errorMsg;
    private Long refId;
    private LocalDateTime createdAt;
}
