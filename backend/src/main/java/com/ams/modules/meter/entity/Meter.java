package com.ams.modules.meter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

@Data
@TableName("meter")
public class Meter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private Long contractId;
    private String meterType; // water/electric/gas
    private String meterNo;
    private BigDecimal multiplier;
    private Integer status;
}
