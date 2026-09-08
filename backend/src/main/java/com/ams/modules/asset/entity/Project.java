package com.ams.modules.asset.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private String name;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Integer status;
}
