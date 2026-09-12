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
 * <p>数据范围的「不受限」与「无可见公司」用 {@link CompanyScope} 在类型上分开，
 * 不再依赖「空集合」的约定 —— 那样一次减法算空就会被误读成全量放开。见设计 5.2。
 */
@Service
public class RbacService {

    private static final long PERM_CACHE_TTL_SECONDS = 30 * 60;

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
     * <p><strong>停用角色即撤销</strong>：角色码、权限、数据范围排除三者都只取
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
            // 只保留已启用角色：停用角色不得再贡献角色码、数据范围（进而影响 unrestricted 判定）
            List<Long> enabledRoleIds = roles.stream()
                    .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                    .map(Role::getId)
                    .toList();
            for (Role role : roles) {
                if (role.getStatus() != null && role.getStatus() == 1) {
                    roleCodes.add(role.getCode());
                    // 取最宽；未知 / null 取值按最窄处理（收敛方向朝安全侧）
                    if (DataScope.isWiderThan(role.getDataScope(), dataScope)) {
                        dataScope = role.getDataScope();
                    }
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
        return switchableScope(homeCompanyId, unrestricted, excluded).allows(targetCompanyId);
    }

    /**
     * 用户可切换公司的范围（已扣除排除子树）。
     *
     * <p>与 {@link #companyScope(LoginUser)} 的差别只有一个：不受限账号这里是<strong>不受限语义</strong>
     * （{@code allows} 对全部启用公司为真），因为切换列表需要「全部公司」这个集合本身；
     * 调用方负责再按启用状态过滤。
     */
    public CompanyScope switchableCompanyScope(LoginUser user) {
        if (user == null) {
            return CompanyScope.of(Set.of());
        }
        boolean unrestricted = !user.isCompanyScoped()
                && (user.isSuperAdmin() || "all".equals(user.getDataScope()));
        return switchableScope(user.getHomeCompanyId(), unrestricted, excludedCompanyIds(user));
    }

    /**
     * 可切换范围的计算。不受限且无排除时直接用不受限语义，避免每次都枚举全表公司。
     */
    private CompanyScope switchableScope(
            Long homeCompanyId, boolean unrestricted, Set<Long> excluded) {
        if (unrestricted && excluded.isEmpty()) {
            return CompanyScope.unrestricted();
        }
        Set<Long> base = unrestricted ? allCompanyIds() : subtree(homeCompanyId);
        return CompanyScope.of(base).minus(excluded);
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
     * <p>可访问范围 = 当前生效公司 + 其全部下级公司（公司子树），再扣除角色排除子树。
     *
     * <p><strong>公司归属为空的记录对受限用户一律拒绝</strong>：归属不明的数据不能被默认放行，
     * 否则「排除」可以通过把记录的公司字段留空来绕过。
     */
    public void assertCompanyAccess(LoginUser user, Long companyId) {
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        CompanyScope scope = companyScope(user);
        if (!scope.hasFilter()) {
            return; // 不受限
        }
        if (!scope.allows(companyId)) {
            throw new AppException(ErrorCode.DATA_SCOPE_FORBIDDEN);
        }
    }

    /**
     * 对象级数据范围的**判定版**（不抛 403），供「越权与不存在必须不可区分」的调用点使用。
     *
     * <p>判定与 {@link #assertCompanyAccess} 完全一致（同一个 {@link #companyScope()}/
     * {@link CompanyScope#hasFilter()}/{@link CompanyScope#allows(Long)}），只是把结果返回而不是抛
     * {@link ErrorCode#DATA_SCOPE_FORBIDDEN}：调用方需要在「越权」时改抛 404，而捕获
     * {@code assertCompanyAccess} 的异常会连它的 {@link ErrorCode#UNAUTHORIZED} 一起吞掉。
     *
     * <p>{@code user == null} 仍抛 {@link ErrorCode#UNAUTHORIZED} —— 未登录不能被降级成「无权限」。
     *
     * <p>不要用本方法替换 {@code assertCompanyAccess} 的既有调用点：那些端点依赖 403 语义。
     *
     * @return {@code true} 表示可访问（不受限账号恒为 true）
     */
    public boolean canAccessCompany(LoginUser user, Long companyId) {
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        CompanyScope scope = companyScope(user);
        if (!scope.hasFilter()) {
            return true; // 不受限
        }
        return scope.allows(companyId);
    }

    /**
     * 返回用户可访问的 company 范围（用于 SQL company_id IN）。
     *
     * <p>只有「未切换公司 + super_admin / dataScope=all + 无任何排除」才是不受限；
     * 其余情况一律收敛为具体 id 集合，减法算空时降级为拒绝哨兵，<strong>绝不</strong>
     * 退回不受限 —— 这是排除清单能被绕过的唯一入口。
     *
     * <p>已切换公司时无论角色如何都收敛为「生效公司 + 下级子树」，使全局公司切换对
     * 高权限账号同样生效（否则切了也没反应）。
     */
    public CompanyScope companyScope(LoginUser user) {
        if (user == null) {
            return CompanyScope.of(Set.of());
        }
        Set<Long> excluded = excludedCompanyIds(user);

        if (user.isCompanyScoped()) {
            // 切换后一律以生效公司为根收窄，不再有「全量」
            return CompanyScope.of(subtree(user.getCompanyId())).minus(excluded);
        }

        boolean unrestricted = user.isSuperAdmin() || "all".equals(user.getDataScope());
        if (unrestricted && excluded.isEmpty()) {
            return CompanyScope.unrestricted();
        }
        // 有排除就必须显式枚举基线，否则「无排除」也会被变成 IN 条件而改变原行为
        Set<Long> base = unrestricted ? allCompanyIds() : subtree(user.getHomeCompanyId());
        return CompanyScope.of(base).minus(excluded);
    }

    /**
     * 把公司数据范围应用到查询条件（供各列表复用，避免逐处重复写子树判断）。
     *
     * <p>全局公司切换后，所有接入本方法的列表都会随之收敛。
     *
     * <p><strong>只覆盖列表查询</strong>：按主键取详情的接口必须另行做对象级断言
     * （{@link #assertCompanyAccess}），否则持有一个 id 就能读到范围外（含被排除）公司的数据。
     *
     * @param companyColumn 实体上承载公司归属的列，如 {@code Asset::getOperatingCompanyId}
     */
    public <T> void applyCompanyScope(
            LambdaQueryWrapper<T> wrapper, LoginUser user, SFunction<T, Long> companyColumn) {
        CompanyScope scope = companyScope(user);
        if (!scope.hasFilter()) {
            return;
        }
        wrapper.in(companyColumn, scope.ids());
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
}
