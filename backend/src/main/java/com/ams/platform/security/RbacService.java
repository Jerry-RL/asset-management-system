package com.ams.platform.security;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.RoleDataExclude;
import com.ams.modules.system.entity.RolePermission;
import com.ams.modules.system.entity.UserRole;
import com.ams.modules.system.mapper.RoleDataExcludeMapper;
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
 *
 * <h2>数据范围的两种「特殊值」—— 混同即越权</h2>
 * <ul>
 *   <li><strong>空集合 = 不受公司限制（UNRESTRICTED）</strong>：调用方按「不加 company 条件」处理。
 *       本类只允许 <em>未切换公司的 super_admin / dataScope=all 且无排除</em> 这一个分支返回空集合。</li>
 *   <li><strong>哨兵 {@code {-1}} = 无任何可见公司</strong>：任何减法（排除清单）算空之后的结果。</li>
 * </ul>
 * 减法（{@code baseline − excluded}）<strong>绝不能</strong>把空结果直接返回成空集合 ——
 * 那会把「排除干净」放大成「放开全量」。见设计 5.2。
 */
@Service
public class RbacService {

    private static final long PERM_CACHE_TTL_SECONDS = 30 * 60;

    /** 哨兵：匹配不到任何公司（取自库中的公司 id 恒为正数）。 */
    private static final Set<Long> NO_COMPANY = Set.of(-1L);

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleDataExcludeMapper roleDataExcludeMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CompanyTreeService companyTreeService;

    public RbacService(
            UserRoleMapper userRoleMapper,
            RoleMapper roleMapper,
            RolePermissionMapper rolePermissionMapper,
            RoleDataExcludeMapper roleDataExcludeMapper,
            RedisTemplate<String, Object> redisTemplate,
            CompanyTreeService companyTreeService) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.roleDataExcludeMapper = roleDataExcludeMapper;
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
     * <p>目标公司来自客户端请求头，属客户端可控值，必须先经可切换范围校验：
     * 不在范围内时静默回落为用户所属公司（不抛错，避免该接口沦为越权探测点）。
     *
     * <p><strong>停用角色即撤销</strong>：角色、权限、数据范围排除三者都只取
     * {@code status = 1} 的角色，停用一个角色会同时撤销它的权限与其排除清单的影响。
     */
    public LoginUser buildLoginUser(User user, String clientType, Long requestedCompanyId) {
        List<UserRole> userRoles =
                userRoleMapper.selectList(
                        new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, user.getId()));

        Set<String> roleCodes = new HashSet<>();
        Set<String> permissions = new HashSet<>();
        Set<Long> excluded = new LinkedHashSet<>();
        String dataScope = "self";

        if (!userRoles.isEmpty()) {
            List<Long> boundRoleIds = userRoles.stream().map(UserRole::getRoleId).distinct().toList();
            List<Role> roles = boundRoleIds.isEmpty() ? List.of() : roleMapper.selectBatchIds(boundRoleIds);
            // 只保留已启用角色：停用角色不得再贡献角色码、数据范围或权限
            List<Long> enabledRoleIds = roles.stream()
                    .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                    .map(Role::getId)
                    .toList();
            for (Role role : roles) {
                if (role.getStatus() != null && role.getStatus() == 1) {
                    roleCodes.add(role.getCode());
                    dataScope = widen(dataScope, role.getDataScope());
                }
            }
            if (!enabledRoleIds.isEmpty()) {
                // 权限同样只装配已启用角色 —— 否则停用角色仍在下发权限
                List<RolePermission> rps = rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermission>()
                                .in(RolePermission::getRoleId, enabledRoleIds));
                for (RolePermission rp : rps) {
                    permissions.add(rp.getMenuCode() + ":" + rp.getAction());
                }
                excluded.addAll(loadExcludedCompanyIds(enabledRoleIds));
            }
        }

        boolean unrestricted = roleCodes.contains("super_admin") || "all".equals(dataScope);
        Long homeCompanyId = user.getCompanyId();
        Long activeCompanyId = homeCompanyId;
        boolean companyScoped = false;
        if (requestedCompanyId != null
                && isSwitchable(homeCompanyId, unrestricted, requestedCompanyId, excluded)) {
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
                .excludedCompanyIds(excluded)
                .build();
    }

    /**
     * 目标公司是否在用户的可切换范围内（含排除清单扣除）。
     *
     * <p>可切换范围 = 用户<strong>所属公司</strong>及其全部下级公司；super_admin / 数据范围 all
     * 为全部启用公司，但<strong>同样扣除排除子树</strong>，保证「能切到」与「能看见」一致。
     * 子树根必须取所属公司而非当前生效公司，否则切入下级公司后就无法再切回上级。
     *
     * <p>无论是否受限，目标公司都必须真实存在且启用 —— 否则会把不存在的公司 ID 写进
     * 当前生效公司，拼出 {@code company_id IN (不存在的ID)}，结果恒为空。
     *
     * @param unrestricted 角色是否不受公司限制（super_admin / 数据范围 all）
     * @param excluded     角色级排除清单展开后的公司集合（含下级子树）
     */
    public boolean isSwitchable(
            Long homeCompanyId, boolean unrestricted, Long targetCompanyId, Set<Long> excluded) {
        if (targetCompanyId == null || !companyTreeService.isActiveCompany(targetCompanyId)) {
            return false;
        }
        return switchableIds(homeCompanyId, unrestricted, excluded).contains(targetCompanyId);
    }

    /**
     * 用户可切换公司的 ID 集合（已扣除排除子树）。
     *
     * <p>与 {@link #companyScope(LoginUser)} 的差别只有一个：不受限账号这里是<strong>显式枚举</strong>
     * 全部公司（切换列表需要具体 id），而不是用空集合表示全量。两者都不会返回空集合 ——
     * 无可见公司时返回哨兵 {@code {-1}}。
     */
    public Set<Long> switchableCompanyIds(LoginUser user) {
        if (user == null) {
            return NO_COMPANY;
        }
        boolean unrestricted = !user.isCompanyScoped()
                && (user.isSuperAdmin() || "all".equals(user.getDataScope()));
        return switchableIds(user.getHomeCompanyId(), unrestricted, excludedCompanyIds(user));
    }

    private Set<Long> switchableIds(
            Long homeCompanyId, boolean unrestricted, Set<Long> excluded) {
        Set<Long> base = unrestricted
                ? allCompanyIds()
                : (homeCompanyId == null ? Set.of() : companyTreeService.descendantIds(homeCompanyId));
        return subtract(base, excluded);
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
     * <p>可访问范围 = 当前生效公司 + 其全部下级公司（公司子树），再扣除角色排除子树；
     * 未切换公司时，super_admin / 数据范围 all 不做限制（保持原口径）。
     *
     * <p><strong>公司归属为空的记录对受限用户一律拒绝</strong>：归属不明的数据不能被默认放行，
     * 否则「排除」可以通过把记录的公司字段留空来绕过。
     */
    public void assertCompanyAccess(LoginUser user, Long companyId) {
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        Set<Long> scope = companyScope(user);
        if (scope.isEmpty()) {
            return; // 不受限
        }
        if (companyId == null || !scope.contains(companyId)) {
            throw new AppException(ErrorCode.DATA_SCOPE_FORBIDDEN);
        }
    }

    /**
     * 返回用户可访问的 company 集合（用于 SQL company_id IN）。
     *
     * <p>空集合表示「不限公司」：<strong>仅当未切换公司、且是 super_admin 或 dataScope=all、
     * 且没有任何排除</strong> 时才返回，使既有调用方的 {@code isEmpty()} 语义保持有效。
     * 其余情况收敛为具体 id 集合；减法算空时返回哨兵 {@code {-1}}，绝不返回空集合。
     *
     * <p>已切换公司时无论角色如何都收敛为「生效公司 + 下级子树」，使全局公司切换对
     * 高权限账号同样生效（否则切了也没反应）。
     *
     * <p>未归属公司且未切换的账号返回哨兵集合 {@code {-1}}，
     * 避免空集合被误判为全量而放大权限。
     */
    public Set<Long> companyScope(LoginUser user) {
        if (user == null) {
            return NO_COMPANY;
        }
        Set<Long> excluded = excludedCompanyIds(user);

        if (user.isCompanyScoped()) {
            // 切换后一律以生效公司为根收窄，不再有「全量」
            return subtract(subtree(user.getCompanyId()), excluded);
        }

        boolean unrestricted = user.isSuperAdmin() || "all".equals(user.getDataScope());
        if (unrestricted) {
            if (excluded.isEmpty()) {
                return Set.of(); // UNRESTRICTED：唯一允许返回空集合的分支
            }
            // 有排除才需要显式枚举，否则会把「无排除」也变成 IN 条件而改变原行为
            return subtract(allCompanyIds(), excluded);
        }
        return subtract(subtree(user.getHomeCompanyId()), excluded);
    }

    /**
     * 把公司数据范围应用到查询条件（供各列表复用，避免逐处重复写子树判断）。
     *
     * <p>可访问集合为空表示「不限公司」，此时不加条件；否则收敛为可访问公司集合。
     * 全局公司切换后，所有接入本方法的列表都会随之收敛。
     *
     * <p><strong>只覆盖列表查询</strong>：按主键取详情的接口必须另行做对象级断言，
     * 否则持有一个 id 就能读到范围外（含被排除）公司的数据。
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

    /** 用户（已启用角色）的排除清单展开集合：每个被排除公司连同其整棵下级子树。 */
    public Set<Long> excludedCompanyIds(LoginUser user) {
        if (user == null || user.getExcludedCompanyIds() == null) {
            return Set.of();
        }
        return user.getExcludedCompanyIds();
    }

    /** 从库中装配排除集合并展开子树（登录装配用）。 */
    private Set<Long> loadExcludedCompanyIds(List<Long> enabledRoleIds) {
        List<RoleDataExclude> rows = roleDataExcludeMapper.selectList(
                new LambdaQueryWrapper<RoleDataExclude>()
                        .in(RoleDataExclude::getRoleId, enabledRoleIds));
        if (rows.isEmpty()) {
            return Set.of();
        }
        Set<Long> expanded = new LinkedHashSet<>();
        for (RoleDataExclude row : rows) {
            // 命中即整棵子树：排除了母公司就不该再看见它的子公司
            expanded.addAll(companyTreeService.descendantIds(row.getCompanyId()));
        }
        return expanded;
    }

    /** 子树（含自身）；rootId 为空时返回空集合。 */
    private Set<Long> subtree(Long rootId) {
        return rootId == null ? Set.of() : companyTreeService.descendantIds(rootId);
    }

    /** 全部公司 id（不受限账号在存在排除时的显式基线）。 */
    private Set<Long> allCompanyIds() {
        return companyTreeService.listCompanies().stream()
                .map(Company::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** {@code base − excluded}；结果为空时返回哨兵，绝不返回空集合。 */
    private Set<Long> subtract(Set<Long> base, Set<Long> excluded) {
        Set<Long> allowed = new LinkedHashSet<>(base);
        allowed.removeAll(excluded);
        return allowed.isEmpty() ? NO_COMPANY : allowed;
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
