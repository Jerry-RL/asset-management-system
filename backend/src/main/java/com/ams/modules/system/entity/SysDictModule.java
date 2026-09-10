package com.ams.modules.system.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统字典模块（如「资产管理字典」）：系统字典一级分组。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_module")
public class SysDictModule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模块编码（全局唯一） */
    private String code;

    /** 模块名称 */
    private String name;

    private Integer sort;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private String remark;

    /** 模块下的字典（查询组装，非表字段） */
    @TableField(exist = false)
    private List<SysDictType> types;
}
