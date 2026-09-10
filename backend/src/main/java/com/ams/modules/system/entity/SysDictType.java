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
 * 系统字典类型（如「资产类型」）：系统字典二级分组，对应右侧字典 Tab。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_type")
public class SysDictType extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属模块 ID */
    private Long moduleId;

    /** 字典编码（全局唯一） */
    private String code;

    /** 字典名称 */
    private String name;

    private Integer sort;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private String remark;

    /** 字典下的字典项（查询组装，非表字段） */
    @TableField(exist = false)
    private List<SysDictItem> items;
}
