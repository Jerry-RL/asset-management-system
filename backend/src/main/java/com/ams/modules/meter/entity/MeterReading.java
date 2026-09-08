package com.ams.modules.meter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("meter_reading")
public class MeterReading {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long meterId;
    private BigDecimal reading;
    private LocalDate readingDate;
    private BigDecimal usage;
    private Long createdBy;
    private LocalDateTime createdAt;
}
