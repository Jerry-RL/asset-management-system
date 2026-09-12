package com.ams.platform.security;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证主体，写入 SecurityContext。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long userId;
    private String username;
    private String name;

    /** 当前生效公司（默认等于 {@link #homeCompanyId}，可由全局公司切换覆盖） */
    private Long companyId;

    /** 用户所属公司（账号绑定，固定不变；全局公司切换的可选范围以此为子树根） */
    private Long homeCompanyId;

    /**
     * 是否已显式切换到 {@link #companyId} 并据此收敛数据范围。
     *
     * <p>{@code true}：公司子树过滤一律以 companyId 为根，super_admin 也不再全量；
     * {@code false}：保持原有语义（super_admin / 数据范围 all 全量，其余按所属公司子树）。
     */
    private boolean companyScoped;

    private Long departmentId;
    private String clientType;

    /** 角色编码集合 */
    private Set<String> roles;

    /** 权限码集合（menuCode:action） */
    private Set<String> permissions;

    /** 数据范围（all/company/dept/project/self，取最宽） */
    private String dataScope;

    /**
     * 角色级数据范围排除清单展开后的公司集合（含各自的下级子树），见设计 5.2。
     *
     * <p>由 {@code buildLoginUser} 从 {@code role_data_exclude} 装配（只取已启用角色），
     * 供 {@code companyScope()} 从基线中扣除。为空表示无排除，不表示不受限。
     */
    @Builder.Default
    private Set<Long> excludedCompanyIds = Set.of();

    public boolean isSuperAdmin() {
        return roles != null && roles.contains("super_admin");
    }

    /** 权限点集合（menuCode:action），只读用途；判定请走 {@link #hasPermission}。 */
    public Set<String> permissionSet() {
        return permissions == null ? Set.of() : permissions;
    }

    public boolean hasPermission(String permission) {
        if (isSuperAdmin()) {
            return true;
        }
        return permissions != null && permissions.contains(permission);
    }
}
