package com.ams.modules.intangible.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("intangible_asset")
public class IntangibleAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String assetNo;
    private String name;
    private String rightsType;
    private BigDecimal originalValue;
    private BigDecimal netValue;
    private String amortizationRule;
    private LocalDate expiryDate;
    private String status;
    private LocalDateTime createdAt;
}
