package com.ams.platform.security;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.User;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.RolePermission;
import com.ams.modules.system.entity.UserRole;
import com.ams.modules.system.mapper.RoleMapper;
import com.ams.modules.system.mapper.RolePermissionMapper;
import com.ams.modules.system.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * RBAC：加载用户角色/权限/数据范围，提供权限与数据范围断言（NFR-SEC-002）。
 */
@Service
public class RbacService {

    private static final long PERM_CACHE_TTL_SECONDS = 30 * 60;

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public RbacService(
            UserRoleMapper userRoleMapper,
            RoleMapper roleMapper,
            RolePermissionMapper rolePermissionMapper,
            RedisTemplate<String, Object> redisTemplate) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将 User 组装为 LoginUser（角色、权限、数据范围）。
     */
    public LoginUser buildLoginUser(User user, String clientType) {
        List<UserRole> userRoles =
                userRoleMapper.selectList(
                        new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, user.getId()));
        Set<String> roleCodes = new HashSet<>();
        Set<String> permissions = new HashSet<>();
        String dataScope = "self";

        if (!userRoles.isEmpty()) {
            List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
            List<Role> roles = roleMapper.selectBatchIds(roleIds);
            for (Role role : roles) {
                if (role.getStatus() != null && role.getStatus() == 1) {
                    roleCodes.add(role.getCode());
                    dataScope = widen(dataScope, role.getDataScope());
                }
            }
            List<RolePermission> rps = rolePermissionMapper.selectList(
                    new LambdaQueryWrapper<RolePermission>().in(RolePermission::getRoleId, roleIds));
            for (RolePermission rp : rps) {
                permissions.add(rp.getMenuCode() + ":" + rp.getAction());
            }
        }

        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .name(user.getName())
                .companyId(user.getCompanyId())
                .departmentId(user.getDepartmentId())
                .clientType(clientType)
                .roles(roleCodes)
                .permissions(permissions)
                .dataScope(dataScope)
                .build();
    }

    /**
     * 断言权限：menuCode:action（super_admin 全通过）。
     */
    public void assertPermission(LoginUser user, String menuCode, String action) {
        if (user == null || !user.hasPermission(menuCode + ":" + action)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * 断言数据范围：company 级访问控制（40301）。
     */
    public void assertCompanyAccess(LoginUser user, Long companyId) {
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (companyId == null || user.isSuperAdmin() || "all".equals(user.getDataScope())) {
            return;
        }
        if ("company".equals(user.getDataScope()) || "dept".equals(user.getDataScope())
                || "project".equals(user.getDataScope()) || "self".equals(user.getDataScope())) {
            if (user.getCompanyId() != null && user.getCompanyId().equals(companyId)) {
                return;
            }
        }
        throw new AppException(ErrorCode.DATA_SCOPE_FORBIDDEN);
    }

    /**
     * 返回用户可访问的 company 集合（用于 SQL company_id IN）。
     * 空集合表示全量（all / super_admin）。
     */
    public Set<Long> companyScope(LoginUser user) {
        if (user == null) {
            return Set.of();
        }
        if (user.isSuperAdmin() || "all".equals(user.getDataScope())) {
            return Set.of();
        }
        if (user.getCompanyId() != null) {
            return Set.of(user.getCompanyId());
        }
        return Set.of();
    }

    private String widen(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        int cur = rank(current);
        int cand = rank(candidate);
        return cand > cur ? candidate : current;
    }

    private int rank(String scope) {
        return switch (scope) {
            case "all" -> 5;
            case "company" -> 4;
            case "dept" -> 3;
            case "project" -> 2;
            case "self" -> 1;
            default -> 0;
        };
    }
}
