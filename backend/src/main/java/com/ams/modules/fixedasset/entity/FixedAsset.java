package com.ams.modules.fixedasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("fixed_asset")
public class FixedAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String assetNo;
    private String name;
    private String assetType;
    private Long companyId;
    private BigDecimal originalValue;
    private BigDecimal netValue;
    private String status;
    private String userName;
    private Long departmentId;
    private String location;
    private LocalDate acquiredAt;
    private LocalDateTime createdAt;
}
