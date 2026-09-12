package com.ams.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 角色权限点（FR-SYS-001）。
 *
 * <p>{@code menuId} 是权威判定依据（V45 起）；{@code menuCode} 是冗余列，
 * 由保存路径从 menuId 解析回填 —— 登录装配仍产出 {@code menuCode:action}，
 * 既有判定逻辑不变。该列在库中为 NOT NULL，回填缺失会写出 {@code null:update}，
 * 导致接口全部 403、侧边栏空掉。
 */
@Data
@TableName("role_permission")
public class RolePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roleId;

    /** 权威关联：menu.id */
    private Long menuId;

    /** 冗余列：menu.code，由 menuId 解析回填（日志可读性与兼容兜底） */
    private String menuCode;

    /** 动作，取值见 {@link com.ams.platform.security.PermissionAction} */
    private String action;

    private LocalDateTime createdAt;
}
