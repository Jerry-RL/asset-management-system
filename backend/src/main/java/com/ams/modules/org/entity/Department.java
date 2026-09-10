package com.ams.modules.org.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门（组织架构图谱中的 department 节点），隶属于某公司，下挂员工。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("department")
public class Department extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属公司 ID */
    private Long companyId;

    /** 部门名称 */
    private String name;

    /** 上级部门 ID（支持部门内多级） */
    private Long parentId;

    /** 部门类型（公司管理字典 → 部门类型） */
    private String type;

    /** 部门负责人（"user" 表 ID） */
    private Long leaderId;

    /** 展示排序 */
    private Integer sort;

    private String remark;

    /** 1 启用 / 0 停用 */
    private Integer status;

    /** 所属公司名称（列表展示用，非表字段） */
    @TableField(exist = false)
    private String companyName;

    /** 上级部门名称（列表展示用，非表字段） */
    @TableField(exist = false)
    private String parentName;
}
