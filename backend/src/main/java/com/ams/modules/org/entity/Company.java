package com.ams.modules.org.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("company")
public class Company extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;
    private String name;
    private String companyType;
    private Integer status;
}
