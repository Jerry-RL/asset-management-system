package com.ams.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 角色级数据范围排除清单（设计 5.2）。
 *
 * <p>语义与全站其他授权相反：<strong>存在即排除</strong>，命中后连同该公司的整棵下级子树
 * 一起从可访问范围中扣除。默认范围（所属公司 + 全部下级子树 / 全部公司）由角色
 * {@code data_scope} 决定，本表只做减法。
 *
 * <p>行随角色删除级联清理（V45 的外键 ON DELETE CASCADE），不会留下孤儿行。
 */
@Data
@TableName("role_data_exclude")
public class RoleDataExclude {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roleId;

    /** 被排除的公司（含其全部下级） */
    private Long companyId;

    private LocalDateTime createdAt;
}
