package com.ams.modules.alert.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("alert_rule")
public class AlertRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private String alertType;
    private String subType;
    private Integer level;
    private String conditionJson;
    private Boolean enabled;
}
