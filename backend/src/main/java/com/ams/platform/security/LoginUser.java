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
    private Long companyId;
    private Long departmentId;
    private String clientType;

    /** 角色编码集合 */
    private Set<String> roles;

    /** 权限码集合（menuCode:action） */
    private Set<String> permissions;

    /** 数据范围（all/company/dept/project/self，取最宽） */
    private String dataScope;

    public boolean isSuperAdmin() {
        return roles != null && roles.contains("super_admin");
    }

    public boolean hasPermission(String permission) {
        if (isSuperAdmin()) {
            return true;
        }
        return permissions != null && permissions.contains(permission);
    }
}
