package com.ams.modules.system.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 角色权限矩阵（设计 4.5 的 {@code GET /system/roles/{roleId}/permissions}）。
 *
 * <p>一次返回渲染矩阵所需的全部内容：菜单树、已授动作、动作词表、已强制校验清单。
 * 分开取会让前端在「菜单树已到、动作词表未到」的中间态渲染出空列。
 */
@Data
@AllArgsConstructor
public class RolePermissionMatrix {

    private Long roleId;

    /** 菜单树（目录 → 菜单，不含 button 类型）。 */
    private List<MenuNode> menus;

    /** 已授权项：{@code menuId + actions}。 */
    private List<Granted> granted;

    /** 固定动作词表。 */
    private List<String> actions;

    /** 已强制校验的 {@code menuCode:action}（前端据此标记「未生效」）。 */
    private List<String> enforced;

    /** 单个菜单已授予的动作。 */
    @Data
    @AllArgsConstructor
    public static class Granted {
        private Long menuId;
        private List<String> actions;
    }
}
