package com.ams.platform.security;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.RolePermission;
import com.ams.modules.system.entity.UserRole;
import com.ams.modules.system.mapper.RoleMapper;
import com.ams.modules.system.mapper.RolePermissionMapper;
import com.ams.modules.system.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private final CompanyTreeService companyTreeService;

    public RbacService(
            UserRoleMapper userRoleMapper,
            RoleMapper roleMapper,
            RolePermissionMapper rolePermissionMapper,
            RedisTemplate<String, Object> redisTemplate,
            CompanyTreeService companyTreeService) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.redisTemplate = redisTemplate;
        this.companyTreeService = companyTreeService;
    }

    /**
     * 将 User 组装为 LoginUser（角色、权限、数据范围）；当前生效公司默认为用户所属公司。
     */
    public LoginUser buildLoginUser(User user, String clientType) {
        return buildLoginUser(user, clientType, null);
    }

    /**
     * 将 User 组装为 LoginUser，并应用「全局公司切换」。
     *
     * <p>目标公司来自客户端请求头，属客户端可控值，必须先经 {@link #isSwitchable} 校验：
     * 不在可切换范围内时静默回落为用户所属公司（不抛错，避免该接口沦为越权探测点）。
     */
    public LoginUser buildLoginUser(User user, String clientType, Long requestedCompanyId) {
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

        boolean unrestricted = roleCodes.contains("super_admin") || "all".equals(dataScope);
        Long homeCompanyId = user.getCompanyId();
        Long activeCompanyId = homeCompanyId;
        boolean companyScoped = false;
        if (requestedCompanyId != null
                && isSwitchable(homeCompanyId, unrestricted, requestedCompanyId)) {
            activeCompanyId = requestedCompanyId;
            companyScoped = true;
        }

        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .name(user.getName())
                .companyId(activeCompanyId)
                .homeCompanyId(homeCompanyId)
                .companyScoped(companyScoped)
                .departmentId(user.getDepartmentId())
                .clientType(clientType)
                .roles(roleCodes)
                .permissions(permissions)
                .dataScope(dataScope)
                .build();
    }

    /**
     * 目标公司是否在用户的可切换范围内。
     *
     * <p>可切换范围 = 用户<strong>所属公司</strong>及其全部下级公司；super_admin / 数据范围 all 不受限。
     * 子树根必须取所属公司而非当前生效公司，否则切入下级公司后就无法再切回上级。
     *
     * <p>无论是否受限，目标公司都必须真实存在且启用 —— 否则会把不存在的公司 ID 写进
     * 当前生效公司，拼出 {@code company_id IN (不存在的ID)}，结果恒为空。
     *
     * @param unrestricted 角色是否不受公司限制（super_admin / 数据范围 all）
     */
    public boolean isSwitchable(Long homeCompanyId, boolean unrestricted, Long targetCompanyId) {
        if (targetCompanyId == null || !companyTreeService.isActiveCompany(targetCompanyId)) {
            return false;
        }
        if (unrestricted) {
            return true;
        }
        return homeCompanyId != null
                && companyTreeService.descendantIds(homeCompanyId).contains(targetCompanyId);
    }

    /**
     * 用户可切换公司的 ID 集合。
     *
     * @return 空集合表示不受限（super_admin / 数据范围 all），由调用方按「全部公司」处理；
     *         用户未归属公司时同样为空，即无可切换范围
     */
    public Set<Long> switchableCompanyIds(LoginUser user) {
        if (user == null || user.isSuperAdmin() || "all".equals(user.getDataScope())) {
            return Set.of();
        }
        return companyTreeService.descendantIds(user.getHomeCompanyId());
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
     *
     * <p>可访问范围 = 当前生效公司 + 其全部下级公司（公司子树）；
     * 未切换公司时，super_admin / 数据范围 all / 未归属公司的账号不做限制（保持原口径）。
     */
    public void assertCompanyAccess(LoginUser user, Long companyId) {
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (companyId == null) {
            return;
        }
        Set<Long> scope = companyScope(user);
        if (scope.isEmpty() || scope.contains(companyId)) {
            return;
        }
        throw new AppException(ErrorCode.DATA_SCOPE_FORBIDDEN);
    }

    /**
     * 返回用户可访问的 company 集合（用于 SQL company_id IN）。
     *
     * <p>空集合表示「不限公司」：super_admin / 数据范围 all，且未显式切换公司。
     * 已切换公司时无论角色如何都收敛为「生效公司 + 下级子树」，使全局公司切换对
     * 高权限账号同样生效（否则切了也没反应）。
     *
     * <p>未归属公司且未切换的账号返回哨兵集合 {@code {-1}}，
     * 避免空集合被误判为全量而放大权限。
     */
    public Set<Long> companyScope(LoginUser user) {
        if (user == null) {
            return Set.of(-1L);
        }
        if (!user.isCompanyScoped()
                && (user.isSuperAdmin() || "all".equals(user.getDataScope()))) {
            return Set.of(); // 空集合 = 全量
        }
        Long root = user.isCompanyScoped() ? user.getCompanyId() : user.getHomeCompanyId();
        if (root == null) {
            return Set.of(-1L); // 哨兵：不匹配任何公司
        }
        Set<Long> subtree = new LinkedHashSet<>();
        subtree.add(root);
        subtree.addAll(companyTreeService.descendantIds(root));
        return subtree;
    }

    /**
     * 把公司数据范围应用到查询条件（供各列表复用，避免逐处重复写子树判断）。
     *
     * <p>可访问集合为空表示「不限公司」，此时不加条件；否则收敛为可访问公司集合。
     * 全局公司切换后，所有接入本方法的列表都会随之收敛。
     *
     * @param companyColumn 实体上承载公司归属的列，如 {@code Asset::getOperatingCompanyId}
     */
    public <T> void applyCompanyScope(
            LambdaQueryWrapper<T> wrapper, LoginUser user, SFunction<T, Long> companyColumn) {
        Set<Long> scope = companyScope(user);
        if (scope.isEmpty()) {
            return;
        }
        wrapper.in(companyColumn, scope);
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
