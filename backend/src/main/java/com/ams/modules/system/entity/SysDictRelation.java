package com.ams.modules.system.entity;

import com.ams.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典关联：字典（source）挂接另一字典（target）下的若干个字典值。
 *
 * <p>两种粒度：
 * <ul>
 *   <li>{@code sourceItemId} 为空：挂在整本字典上，属描述性关联，不参与提交校验；
 *   <li>{@code sourceItemId} 非空：挂在父字典的某个字典项上，作为子字典下拉的可见项白名单，
 *       即「父字典该项 → 子字典可见的若干项」的级联规则。
 * </ul>
 *
 * <p>按字典 ID / 字典项 ID 关联，字典编码或字典值改名不影响关联关系。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_relation")
public class SysDictRelation extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 拥有该关联的字典 ID */
    private Long sourceTypeId;

    /** 父字典项 ID；为空表示挂在整本字典上（字典级关联） */
    private Long sourceItemId;

    /** 被关联的字典 ID */
    private Long targetTypeId;

    /** 被关联的字典项 ID */
    private Long targetItemId;

    private Integer sort;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private String remark;
}
