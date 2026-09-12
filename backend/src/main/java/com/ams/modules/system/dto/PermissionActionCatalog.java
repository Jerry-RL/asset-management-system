package com.ams.modules.system.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 动作词表 + 已强制校验清单（设计 4.5 的 {@code GET /system/permission-actions}）。
 *
 * <p>前端权限矩阵的「尚未强制校验」标识必须来自 {@link #enforced}，不得硬编码 ——
 * 该字段由 {@code PermissionRegistry} 在启动时扫描 {@code @RequiresPerm} 生成。
 */
@Data
@AllArgsConstructor
public class PermissionActionCatalog {

    /** 固定动作词表（顺序即矩阵列顺序）。 */
    private List<String> actions;

    /** 已强制校验的 {@code menuCode:action}（后端拦截器会真正判定）。 */
    private List<String> enforced;
}
