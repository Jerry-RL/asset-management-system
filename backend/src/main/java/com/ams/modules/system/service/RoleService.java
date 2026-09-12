package com.ams.modules.system.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.system.dto.MenuNode;
import com.ams.modules.system.dto.PermissionActionCatalog;
import com.ams.modules.system.dto.RoleDataScopeView;
import com.ams.modules.system.dto.RolePermissionBatch;
import com.ams.modules.system.dto.RolePermissionMatrix;
import com.ams.modules.system.entity.Menu;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.RoleDataExclude;
import com.ams.modules.system.entity.RolePermission;
import com.ams.modules.system.entity.UserRole;
import com.ams.modules.system.mapper.MenuMapper;
import com.ams.modules.system.mapper.RoleDataExcludeMapper;
import com.ams.modules.system.mapper.RoleMapper;
import com.ams.modules.system.mapper.RolePermissionMapper;
import com.ams.modules.system.mapper.UserRoleMapper;
import com.ams.platform.security.DataScope;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.PermissionAction;
import com.ams.platform.security.PermissionRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 角色与权限管理（FR-SYS-001）：角色 CRUD、权限矩阵、数据范围排除清单、提权约束。
 *
 * <h2>提权约束（设计 4.5）</h2>
 * 以下三条对<strong>非 super_admin</strong> 的调用者强制生效（super_admin 是唯一例外，
 * 用于首次授权引导）：
 * <ol>
 *   <li>只能授予自己已持有的动作的子集；</li>
 *   <li>不得修改任何已分配给自己的角色；</li>
 *   <li>写入的 {@code dataScope} 不得宽于自身 —— 只管动作子集而不管数据范围，
 *       等于给「有页面权限的人」留了造出全集团角色的后门。</li>
 * </ol>
 * 「必须持有 {@code system.role:assign}」由控制器上的
 * {@link com.ams.platform.security.RequiresPerm} 负责，不在此重复。
 */
@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleDataExcludeMapper roleDataExcludeMapper;
    private final UserRoleMapper userRoleMapper;
    private final MenuMapper menuMapper;
    private final MenuService menuService;
    private final CompanyTreeService companyTreeService;
    private final PermissionRegistry permissionRegistry;

    public RoleService(
            RoleMapper roleMapper,
            RolePermissionMapper rolePermissionMapper,
            RoleDataExcludeMapper roleDataExcludeMapper,
            UserRoleMapper userRoleMapper,
            MenuMapper menuMapper,
            MenuService menuService,
            CompanyTreeService companyTreeService,
            PermissionRegistry permissionRegistry) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.roleDataExcludeMapper = roleDataExcludeMapper;
        this.userRoleMapper = userRoleMapper;
        this.menuMapper = menuMapper;
        this.menuService = menuService;
        this.companyTreeService = companyTreeService;
        this.permissionRegistry = permissionRegistry;
    }

    public List<Role> listRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getId));
    }

    /** 动作词表 + 已强制校验清单（供矩阵渲染，前端不得硬编码）。 */
    public PermissionActionCatalog actionCatalog() {
        return new PermissionActionCatalog(PermissionAction.codes(), enforcedFor(allMenuCodes()));
    }

    public Role createRole(Role role, LoginUser caller) {
        if (!StringUtils.hasText(role.getCode())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "角色编码不能为空");
        }
        if (!StringUtils.hasText(role.getName())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "角色名称不能为空");
        }
        if (roleMapper.selectCount(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, role.getCode())) > 0) {
            throw new AppException(ErrorCode.CONFLICT, "角色编码已存在");
        }
        assertDataScopeNotWider(role.getDataScope(), caller);
        role.setId(null);
        if (role.getStatus() == null) {
            role.setStatus(1);
        }
        roleMapper.insert(role);
        return role;
    }

    public Role updateRole(Long id, Role role, LoginUser caller) {
        Role existing = requireRole(id);
        assertDataScopeNotWider(role.getDataScope(), caller);
        // code 是权限命名空间的对端标识，改名会让既有配置失去可读锚点，不在本接口能力范围内
        role.setId(id);
        role.setCode(existing.getCode());
        roleMapper.updateById(role);
        return roleMapper.selectById(id);
    }

    /**
     * 删除角色。
     *
     * <p>仍有成员绑定时拒绝：静默解绑会让这些账号在下次登录时凭空失去全部权限，
     * 且没有任何提示说明原因。
     */
    @Transactional
    public void deleteRole(Long id) {
        requireRole(id);
        Long members = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, id));
        if (members != null && members > 0) {
            throw new AppException(
                    ErrorCode.BAD_REQUEST, "该角色仍有 " + members + " 名成员，请先移除成员");
        }
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        roleDataExcludeMapper.delete(
                new LambdaQueryWrapper<RoleDataExclude>().eq(RoleDataExclude::getRoleId, id));
        roleMapper.deleteById(id);
    }

    /** 角色权限矩阵：菜单树 + 已授动作 + 动作词表 + 已强制校验清单。 */
    public RolePermissionMatrix permissionsOf(Long roleId) {
        requireRole(roleId);
        List<MenuNode> tree = menuService.adminTree();
        List<RolePermission> granted = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        Map<Long, Set<String>> actionsByMenu = new LinkedHashMap<>();
        for (RolePermission rp : granted) {
            if (rp.getMenuId() == null) {
                continue;
            }
            actionsByMenu.computeIfAbsent(rp.getMenuId(), k -> new LinkedHashSet<>())
                    .add(rp.getAction());
        }
        List<RolePermissionMatrix.Granted> rows = new ArrayList<>();
        actionsByMenu.forEach((menuId, actions) ->
                rows.add(new RolePermissionMatrix.Granted(menuId, new ArrayList<>(actions))));
        return new RolePermissionMatrix(
                roleId,
                tree,
                rows,
                PermissionAction.codes(),
                enforcedFor(allMenuCodes()));
    }

    /** 严格档下会把变更类动作整体计入，因此必须把菜单编码一并交给台账计算。 */
    private List<String> enforcedFor(List<String> menuCodes) {
        return permissionRegistry.effectiveEnforced(menuCodes);
    }

    private List<String> allMenuCodes() {
        return menuMapper.selectList(new LambdaQueryWrapper<Menu>().select(Menu::getCode))
                .stream()
                .map(Menu::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 批量替换角色权限。
     *
     * <p>按 {@code menuId} 落库、由 {@code menuId} 解析回填冗余列 {@code menuCode} ——
     * 该列在库中为 NOT NULL，回填缺失会写出 {@code null:update}，导致该角色的接口全部 403、
     * 侧边栏空掉。因此这里绝不接受客户端直接提交的 {@code menuCode}。
     */
    @Transactional
    public void savePermissions(Long roleId, RolePermissionBatch batch, LoginUser caller) {
        requireRole(roleId);
        assertNotOwnRole(roleId, caller);

        List<RolePermissionBatch.Item> items =
                batch == null || batch.getItems() == null ? List.of() : batch.getItems();
        // 先整体校验再落库：半成品写入会让角色处在「部分授权」状态而调用方收到 403
        List<RolePermission> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RolePermissionBatch.Item item : items) {
            if (item.getMenuId() == null || item.getActions() == null) {
                continue;
            }
            Menu menu = menuMapper.selectById(item.getMenuId());
            if (menu == null) {
                throw new AppException(ErrorCode.NOT_FOUND, "菜单不存在：" + item.getMenuId());
            }
            for (String action : item.getActions()) {
                if (!PermissionAction.isValid(action)) {
                    throw new AppException(
                            ErrorCode.BAD_REQUEST,
                            "动作不在词表 " + PermissionAction.codes() + " 内：" + action);
                }
                String perm = menu.getCode() + ":" + action;
                if (!seen.add(perm)) {
                    continue; // 同一 (menu, action) 重复提交，去重后写一行
                }
                assertGrantable(perm, caller);
                RolePermission rp = new RolePermission();
                rp.setRoleId(roleId);
                rp.setMenuId(menu.getId());
                rp.setMenuCode(menu.getCode());
                rp.setAction(action);
                rows.add(rp);
            }
        }

        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        for (RolePermission rp : rows) {
            rp.setId(null);
            rolePermissionMapper.insert(rp);
        }
    }

    /** 数据范围视图：默认范围说明 + 排除清单 + 候选公司树。 */
    public RoleDataScopeView dataScopeOf(Long roleId, LoginUser caller) {
        Role role = requireRole(roleId);
        List<Long> excluded = roleDataExcludeMapper.selectList(
                        new LambdaQueryWrapper<RoleDataExclude>()
                                .eq(RoleDataExclude::getRoleId, roleId))
                .stream()
                .map(RoleDataExclude::getCompanyId)
                .toList();
        List<RoleDataScopeView.CompanyNode> companies = companyTreeService.listCompaniesTreeOrdered()
                .stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                .map(c -> new RoleDataScopeView.CompanyNode(
                        c.getId(), c.getName(), c.getShortName(), c.getParentId()))
                .toList();
        DataScope scope = DataScope.of(role.getDataScope()).orElse(DataScope.SELF);
        // 本期未实现的取值要如实列出，避免管理员以为已经限制了范围
        List<String> degraded = switch (scope) {
            case DEPT, PROJECT, SELF -> List.of(scope.code());
            default -> List.of();
        };
        Long homeCompanyId = caller == null ? null : caller.getHomeCompanyId();
        return new RoleDataScopeView(
                roleId,
                role.getDataScope(),
                scope.effectiveDescription(),
                degraded,
                excluded,
                companies,
                homeCompanyId == null ? List.of() : List.of(homeCompanyId));
    }

    /**
     * 保存排除清单（勾选即排除，命中即整棵子树）。
     *
     * <p>不允许排除调用者自己所属公司：那会把自己连同其下级一起锁在门外，
     * 且因为排除项保存在被排除方看不到的角色配置里，事后很难诊断。
     */
    @Transactional
    public void saveDataScope(Long roleId, List<Long> companyIds, LoginUser caller) {
        requireRole(roleId);
        assertNotOwnRole(roleId, caller);

        List<Long> requested = companyIds == null ? List.of() : companyIds;
        Set<Long> valid = new LinkedHashSet<>();
        for (Long companyId : requested) {
            if (companyId == null) {
                continue;
            }
            if (!companyTreeService.isActiveCompany(companyId)) {
                throw new AppException(ErrorCode.NOT_FOUND, "公司不存在或已停用：" + companyId);
            }
            if (caller != null && companyId.equals(caller.getHomeCompanyId())) {
                throw new AppException(
                        ErrorCode.BAD_REQUEST, "不能排除自己所属公司，否则会把自己锁在门外");
            }
            valid.add(companyId);
        }

        roleDataExcludeMapper.delete(
                new LambdaQueryWrapper<RoleDataExclude>().eq(RoleDataExclude::getRoleId, roleId));
        for (Long companyId : valid) {
            RoleDataExclude row = new RoleDataExclude();
            row.setRoleId(roleId);
            row.setCompanyId(companyId);
            roleDataExcludeMapper.insert(row);
        }
    }

    // ---- 提权约束 ----

    /** 调用者只能授予自己已持有的动作（{@code super_admin} 例外，见类注释）。 */
    private void assertGrantable(String permission, LoginUser caller) {
        if (caller == null || caller.isSuperAdmin()) {
            return;
        }
        if (!caller.hasPermission(permission)) {
            throw new AppException(ErrorCode.FORBIDDEN, "不能授予自己未持有的权限：" + permission);
        }
    }

    /** 不得修改已分配给自己的角色。 */
    private void assertNotOwnRole(Long roleId, LoginUser caller) {
        if (caller == null || caller.isSuperAdmin()) {
            return;
        }
        Long bound = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, caller.getUserId())
                .eq(UserRole::getRoleId, roleId));
        if (bound != null && bound > 0) {
            throw new AppException(ErrorCode.FORBIDDEN, "不能修改自己所属角色的权限或数据范围");
        }
    }

    /** 新数据范围不得宽于调用者自身（无 all 者不得授予 all）。 */
    private void assertDataScopeNotWider(String requested, LoginUser caller) {
        if (caller == null || caller.isSuperAdmin() || requested == null) {
            return;
        }
        if (DataScope.isWiderThan(requested, caller.getDataScope())) {
            throw new AppException(
                    ErrorCode.FORBIDDEN,
                    "不能授予宽于自身的数据范围：" + requested + "（自身为 " + caller.getDataScope() + "）");
        }
    }

    private Role requireRole(Long roleId) {
        Role role = roleId == null ? null : roleMapper.selectById(roleId);
        if (role == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }
}
