package com.ams.modules.org.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公司（组织架构图谱中的 company 节点）。
 *
 * <p>通过 {@code parentId} 构成「母公司 → 子公司 → 子公司」的公司树；
 * 公司下挂部门，部门下挂员工（见 {@link Department}、{@link User}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("company")
public class Company extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上级公司 ID；为空表示母公司（图谱根节点） */
    private Long parentId;

    /** 公司名称 */
    private String name;

    /** 公司简称 */
    private String shortName;

    /** 公司类型（公司管理字典 → 公司类型） */
    private String companyType;

    /** 公司地址 */
    private String address;

    /** 联系电话 */
    private String phone;

    /** 展示排序 */
    private Integer sort;

    /** 1 启用 / 0 停用 */
    private Integer status;

    /** 上级公司名称（列表展示用，非表字段） */
    @TableField(exist = false)
    private String parentName;
}
