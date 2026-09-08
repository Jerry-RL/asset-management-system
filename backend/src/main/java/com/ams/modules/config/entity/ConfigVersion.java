package com.ams.modules.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("config_version")
public class ConfigVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;
    private String configValue;
    private LocalDate effectiveDate;
    private Integer version;
    private Long operatorId;
    private String oldValue;
    private String newValue;
    private LocalDateTime createdAt;
}
