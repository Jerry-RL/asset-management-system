package com.ams.modules.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("mortgage")
public class Mortgage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private String mortgagee;
    private BigDecimal amount;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status; // active / released
    private Long fileId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
