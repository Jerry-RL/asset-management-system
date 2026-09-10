package com.ams.modules.system.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统字典项（如「廉租房」）：系统字典三级明细，可对单个字典单独新增。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_item")
public class SysDictItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属字典类型 ID */
    private Long typeId;

    /** 字典值/编码（同一字典内唯一） */
    private String value;

    /** 字典名称/显示标签 */
    private String label;

    private Integer sort;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private String remark;
}
