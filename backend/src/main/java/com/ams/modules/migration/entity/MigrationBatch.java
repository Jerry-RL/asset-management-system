package com.ams.modules.migration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("migration_batch")
public class MigrationBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate cutoverDate;
    private String status; // importing/reconciled/locked
    private String sourceFile;
    private BigDecimal balanceResult;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime lockedAt;
}
