package com.ams.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.modules.org.entity.User;
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
import com.ams.modules.system.service.MenuService;
import com.ams.modules.system.service.RoleService;
import com.ams.platform.security.CompanyScope;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.PermissionRegistry;
import com.ams.platform.security.RbacService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 角色 / 权限 / 人员测试夹具（角色权限矩阵与数据隔离的场景数据）。
 *
 * <h2>覆盖的三类场景</h2>
 * <ol>
 *   <li><b>角色写权限</b>：{@link #grant} 按固定动作词表给各角色授予真实存在的 {@code menuCode:action}
 *       （取自后端 {@code @RequiresPerm} 的实际取值）。{@link #ROLE_LEADER} **刻意只给只读**，
 *       作为 D18「不给存量角色回填写动作」判断的对照 —— 决策层不该因为「懒得配」而拿到写权限。</li>
 *   <li><b>数据范围排除</b>：{@link #exclude} 造出 {@code role_data_exclude} 行，含设计 D15 的关键组合
 *       ——「父公司被排除」与「子公司在同一角色上也被显式排除」同时存在。DB 里两行都留着，
 *       展开交给 {@code descendantIds}（与生产一致），前端三态互斥规则则由 roleDraft 的用例单独覆盖。</li>
 *   <li><b>人员</b>：{@link #person} 覆盖集团级账号（{@link #USER_CHAIRMAN}，验证「A 为 B 上级时默认可见 B」）、
 *       {@code dataScope=all} 的**非超管**账号（{@link #USER_GROUP_STEWARD}，验证「all 仍受排除约束」）、
 *       以及绑定**停用角色**的账号（{@link #USER_LEGACY}，验证「停用即撤销」）。</li>
 * </ol>
 *
 * <h2>如何装配 LoginUser</h2>
 * {@link #loginUser(String)} 走**真实的** {@link RbacService#buildLoginUser}，因此断言覆盖的是
 * 生产装配路径（多角色取最宽、停用角色过滤、排除清单展开），而不是手搓一个 LoginUser 去迎合断言。
 *
 * <h2>角色管理（RoleService）</h2>
 * {@link #newRoleService()} 复用同一批 mapper 桩，因此「调用者的权限」与「角色被写入了什么权限」
 * 在同一份数据上求值：提权约束（只能授自己持有的动作 / 不能改自己的角色 / 数据范围不得更宽）
 * 的调用者由 {@link #loginUser(String)} 真实装配而来，写入口径由
 * {@link #insertedPermissions()} / {@link #insertedExcludes()} / {@link #insertedRoles()} 断言。
 * 计数类查询（成员数、角色码唯一性）的桩限制见 {@link #newRoleService()} 的说明。
 *
 * <h2>桩的边界（改动时必须同步）</h2>
 * Mockito 读不出 MyBatis-Plus {@code LambdaQueryWrapper} 里的 where 条件，因此
 * {@link #newService(long)} **按用户已有的角色 id 复刻了 SQL 的过滤语义**：绑定关系按 user_id 过滤、
 * 角色按 role_id 过滤、权限与排除仅按**启用**角色过滤（对应生产里
 * {@code status = 1} 的 {@code enabledRoleIds}）。若生产的查询条件变了，这里必须跟着改，
 * 否则会出现「夹具通过、生产不对」的假绿灯。
 */
public final class RbacFixtures {

    // ---- 角色编码（与 V2 种子一致；后两个是本夹具为设计边界场景新增的） ----
    public static final String ROLE_SUPER_ADMIN = "super_admin";
    public static final String ROLE_OPERATOR = "operator";
    public static final String ROLE_ASSET_MGR = "asset_mgr";
    public static final String ROLE_FINANCE = "finance";
    public static final String ROLE_LEADER = "leader";
    public static final String ROLE_MAINTENANCE = "maintenance";
    public static final String ROLE_APPROVER = "approver";
    public static final String ROLE_CLERK = "clerk";
    /** 非超管但 {@code dataScope=all}：验证「all 仍受排除清单约束」。 */
    public static final String ROLE_DATA_STEWARD = "data_steward";
    /** 已停用角色（status=0）且带有写权限与排除：验证「停用即撤销」。 */
    public static final String ROLE_DISABLED = "disabled_role";

    // ---- 账号 ----
    public static final String USER_ADMIN = "admin";
    public static final String USER_CHAIRMAN = "chairman";
    public static final String USER_GROUP_STEWARD = "groupsteward";
    public static final String USER_OPERATOR = "operator";
    public static final String USER_ASSET_MGR = "assetmgr";
    public static final String USER_FINANCE = "finance";
    public static final String USER_LEADER = "leader";
    public static final String USER_MAINTENANCE = "maintenance";
    public static final String USER_APPROVER = "approver";
    public static final String USER_CLERK = "clerk";
    public static final String USER_LEGACY = "legacy";
    /** 挂在子公司的运营账号：验证「切入某公司后范围收窄为其子树」。 */
    public static final String USER_COMMERCIAL = "commercial01";

    // ---- 部门（夹具只用到 id，不建 department 表数据） ----
    private static final long DEPT_GROUP_OFFICE = 101L;
    private static final long DEPT_ASSET = 201L;
    private static final long DEPT_FINANCE = 202L;
    private static final long DEPT_OPS = 203L;
    private static final long DEPT_LEGAL = 204L;

    private final CompanyTreeFixtures org;
    private final List<Role> roles = new ArrayList<>();
    private final List<RolePermission> permissions = new ArrayList<>();
    private final List<RoleDataExclude> excludes = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private final List<UserRole> bindings = new ArrayList<>();
    /** 菜单：权限码必须挂在真实菜单上（生产里 role_permission.menu_id 是外键）。 */
    private final List<Menu> menus = new ArrayList<>();
    /** menuCode → menuId，供 {@link #grant(String, String...)} 解析。 */
    private final Map<String, Long> menuIdByCode = new LinkedHashMap<>();

    // ---- 写入记录：断言「落库了什么」 ----
    private final List<RolePermission> insertedPermissions = new ArrayList<>();
    private final List<RoleDataExclude> insertedExcludes = new ArrayList<>();
    private final List<Role> insertedRoles = new ArrayList<>();

    private final UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final RolePermissionMapper rolePermissionMapper = mock(RolePermissionMapper.class);
    private final RoleDataExcludeMapper roleDataExcludeMapper = mock(RoleDataExcludeMapper.class);
    private final MenuMapper menuMapper = mock(MenuMapper.class);
    private final MenuService menuService = mock(MenuService.class);
    private final PermissionRegistry permissionRegistry = mock(PermissionRegistry.class);

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

    private long nextRoleId = 1000L;

    private RbacFixtures(CompanyTreeFixtures org) {
        this.org = org;
        stubWrites();
        // 计数类的默认桩：无成员、不重码、非自身角色。需要时由用例用 when(...) 覆盖。
        // 注意 Mockito 读不出 LambdaQueryWrapper 里的条件，因此这里只能给「整体」的答案，
        // 无法按 userId / roleId 分别回答 —— 相关用例必须显式覆盖并带上反向对照，
        // 否则「恰好返回 0」会让断言因为错误的原因通过。
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.selectCount(any())).thenReturn(0L);
    }

    /** 把 insert 变成可断言的写入记录（MyBatis-Plus 的 insert 返回影响行数）。 */
    private void stubWrites() {
        // 必须用带类型的 any(X.class)：BaseMapper 的 insert / updateById 都有
        // (T) 与 (Collection<T>) 两个重载，裸 any() 会因「对 insert 的引用不明确」编译失败
        when(rolePermissionMapper.insert(any(RolePermission.class))).thenAnswer(invocation -> {
            insertedPermissions.add(invocation.getArgument(0));
            return 1;
        });
        when(roleDataExcludeMapper.insert(any(RoleDataExclude.class))).thenAnswer(invocation -> {
            insertedExcludes.add(invocation.getArgument(0));
            return 1;
        });
        when(roleMapper.insert(any(Role.class))).thenAnswer(invocation -> {
            Role inserted = invocation.getArgument(0);
            if (inserted.getId() == null) {
                inserted.setId(nextRoleId++);
            }
            roles.add(inserted);
            insertedRoles.add(inserted);
            return 1;
        });
        // updateById 要同步到内存列表，否则随后的 selectById 会读到旧值
        when(roleMapper.updateById(any(Role.class))).thenAnswer(invocation -> {
            Role updated = invocation.getArgument(0);
            roles.replaceAll(r -> Objects.equals(r.getId(), updated.getId()) ? updated : r);
            return 1;
        });
        when(roleMapper.selectById(any())).thenAnswer(invocation ->
                roles.stream()
                        .filter(r -> Objects.equals(r.getId(), invocation.getArgument(0)))
                        .findFirst()
                        .orElse(null));
        when(menuMapper.selectById(any())).thenAnswer(invocation ->
                menus.stream()
                        .filter(m -> Objects.equals(m.getId(), invocation.getArgument(0)))
                        .findFirst()
                        .orElse(null));
        when(menuMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(menus));
        // 台账为空：permissionsOf / actionCatalog 的「未生效」标记不属于本夹具的断言范围
        when(permissionRegistry.effectiveEnforced(any())).thenReturn(List.of());
        when(menuService.adminTree()).thenReturn(List.of());
    }

    /** 标准场景：8 个既有角色 + 2 个边界角色、12 个账号、6 条排除（覆盖 5 个角色）。 */
    public static RbacFixtures standard() {
        RbacFixtures f = new RbacFixtures(CompanyTreeFixtures.standard());

        // 菜单先于授权定义：权限码必须挂在真实菜单上（grant 会据此解析 menu_id）
        f.defineMenus();

        // ---- 角色（1-8 与 V2 种子一致，id 也保持一致便于与库内数据对照） ----
        f.role(1, ROLE_SUPER_ADMIN, "系统管理员", "all", 1);
        f.role(2, ROLE_OPERATOR, "运营管理员", "company", 1);
        f.role(3, ROLE_ASSET_MGR, "资产管理员", "company", 1);
        f.role(4, ROLE_FINANCE, "财务人员", "company", 1);
        f.role(5, ROLE_LEADER, "决策层/领导", "company", 1);
        f.role(6, ROLE_MAINTENANCE, "维修管理员", "company", 1);
        f.role(7, ROLE_APPROVER, "审批人员", "company", 1);
        f.role(8, ROLE_CLERK, "办事员/业务员", "dept", 1);
        f.role(9, ROLE_DATA_STEWARD, "集团数据管理员", "all", 1);
        f.role(10, ROLE_DISABLED, "已停用角色", "all", 0);

        // ---- 写权限授予 ----
        // 动作全部取自后端 @RequiresPerm 里真实存在的 57 个码（本文件由
        // scripts/check-perm-invariants.mjs 的「夹具 ⊆ 后端」检查守住，
        // 编造一个不存在的码会让用例在构建期失败，而不是静默成为永不命中的授权）。
        // 注意：本期没有任何 :export / :import 被强制，所以取不到「导出」这类动作，
        // 相应的按钮也不该被 perm 门控（见设计 11.4）。
        f.grant(ROLE_OPERATOR,
                "asset.project:view", "asset.project:update",
                "asset.ledger:view", "asset.ledger:create", "asset.ledger:update",
                "contract.ledger:view", "contract.ledger:create", "contract.ledger:update",
                "contract.vacate:view", "contract.vacate:create", "contract.vacate:update",
                "billing.bill:view", "finance.payment:view",
                "system.dict:view", "org.user:view");
        f.grant(ROLE_ASSET_MGR,
                "asset.project:view", "asset.project:create", "asset.project:update", "asset.project:delete",
                "asset.ledger:view", "asset.ledger:create", "asset.ledger:update", "asset.ledger:delete",
                "asset.structureLog:view", "org.structure:view", "org.company:view");
        f.grant(ROLE_FINANCE,
                "billing.bill:view", "billing.bill:create",
                "finance.payment:view", "finance.payment:create", "finance.payment:update",
                "finance.refund:view", "finance.refund:create", "finance.refund:update",
                "finance.invoice:view", "finance.invoice:create", "finance.invoice:update",
                "finance.voucher:view", "finance.voucher:create", "finance.voucher:update",
                "finance.bankFlow:view", "finance.bankFlow:create", "finance.bankFlow:update");
        // 决策层刻意只读：D18 拒绝「按现有可达能力回填写动作」的对照实现
        f.grant(ROLE_LEADER,
                "asset.project:view", "asset.ledger:view", "asset.structureLog:view",
                "contract.ledger:view", "billing.bill:view", "finance.payment:view",
                "org.structure:view");
        // 维修管理员：首批里没有维修模块的动作码，给资产/结构的只读
        f.grant(ROLE_MAINTENANCE,
                "asset.ledger:view", "asset.structureLog:view");
        f.grant(ROLE_APPROVER,
                "asset.project:view", "billing.bill:view",
                "contract.ledger:view", "contract.ledger:approve");
        f.grant(ROLE_CLERK,
                "asset.ledger:view", "asset.ledger:create",
                "contract.ledger:view", "billing.bill:view");
        f.grant(ROLE_DATA_STEWARD,
                "asset.ledger:view", "asset.structureLog:view", "org.structure:view",
                "org.company:view", "finance.bankFlow:view", "system.dict:view");
        // 停用角色给了写权限与排除：用于证明它们一点都不会生效
        f.grant(ROLE_DISABLED,
                "asset.ledger:view", "asset.ledger:delete", "org.user:delete",
                "finance.payment:update", "system.role:assign");

        // ---- 数据范围排除 ----
        // 运营：文旅集团整棵排除，且其下级古城文旅**也被显式排除**（设计 D15 的关键组合）
        f.exclude(ROLE_OPERATOR, CompanyTreeFixtures.CULTURE);
        f.exclude(ROLE_OPERATOR, CompanyTreeFixtures.ANCIENT);
        // 财务：同级无隶属的水务集团
        f.exclude(ROLE_FINANCE, CompanyTreeFixtures.WATER);
        // 办事员：dept 退化为公司级后再扣除一个下级
        f.exclude(ROLE_CLERK, CompanyTreeFixtures.PROPERTY);
        // 集团数据管理员：dataScope=all 也要受排除约束
        f.exclude(ROLE_DATA_STEWARD, CompanyTreeFixtures.CULTURE);
        // 停用角色：本条不得产生任何影响
        f.exclude(ROLE_DISABLED, CompanyTreeFixtures.COMMERCIAL);

        // ---- 人员 ----
        f.person(1, USER_ADMIN, "系统管理员", CompanyTreeFixtures.GROUP, DEPT_GROUP_OFFICE, ROLE_SUPER_ADMIN, 1);
        f.person(2, USER_CHAIRMAN, "集团董事长", CompanyTreeFixtures.GROUP, DEPT_GROUP_OFFICE, ROLE_LEADER, 1);
        f.person(3, USER_GROUP_STEWARD, "集团数据管理员", CompanyTreeFixtures.GROUP, DEPT_GROUP_OFFICE,
                ROLE_DATA_STEWARD, 1);
        f.person(4, USER_OPERATOR, "运营管理员", CompanyTreeFixtures.SUB, DEPT_OPS, ROLE_OPERATOR, 1);
        f.person(5, USER_ASSET_MGR, "资产管理员", CompanyTreeFixtures.SUB, DEPT_ASSET, ROLE_ASSET_MGR, 1);
        f.person(6, USER_FINANCE, "财务人员", CompanyTreeFixtures.SUB, DEPT_FINANCE, ROLE_FINANCE, 1);
        f.person(7, USER_LEADER, "决策层领导", CompanyTreeFixtures.SUB, DEPT_ASSET, ROLE_LEADER, 1);
        f.person(8, USER_MAINTENANCE, "维修管理员", CompanyTreeFixtures.SUB, DEPT_OPS, ROLE_MAINTENANCE, 1);
        f.person(9, USER_APPROVER, "审批人员", CompanyTreeFixtures.SUB, DEPT_LEGAL, ROLE_APPROVER, 1);
        f.person(10, USER_CLERK, "办事员", CompanyTreeFixtures.SUB, DEPT_ASSET, ROLE_CLERK, 1);
        f.person(11, USER_LEGACY, "历史账号（停用角色）", CompanyTreeFixtures.SUB, DEPT_ASSET, ROLE_DISABLED, 1);
        f.person(12, USER_COMMERCIAL, "商业运营专员", CompanyTreeFixtures.COMMERCIAL, DEPT_OPS,
                ROLE_OPERATOR, 1);
        return f;
    }

    // ---------------------------------------------------------------------
    // 场景构造
    // ---------------------------------------------------------------------

    public RbacFixtures role(long id, String code, String name, String dataScope, int status) {
        Role r = new Role();
        r.setId(id);
        r.setCode(code);
        r.setName(name);
        r.setDataScope(dataScope);
        r.setStatus(status);
        roles.add(r);
        return this;
    }

    /**
     * 授予权限，入参为 {@code menuCode:action}（与后端 {@code @RequiresPerm} 同格式）。
     *
     * <p>menuCode 必须是夹具里**已定义的菜单**：生产里 {@code role_permission.menu_id} 指向
     * 真实菜单行、{@code menu_code} 由它解析回填，写一个不存在的菜单码在这里就抛错，
     * 而不是静默造出一条永不命中的授权。
     */
    public RbacFixtures grant(String roleCode, String... permissionCodes) {
        Role role = role(roleCode);
        for (String code : permissionCodes) {
            int idx = code.lastIndexOf(':');
            if (idx <= 0 || idx == code.length() - 1) {
                throw new IllegalArgumentException("权限码必须形如 menuCode:action，实际为 " + code);
            }
            String menuCode = code.substring(0, idx);
            Long menuId = menuIdByCode.get(menuCode);
            if (menuId == null) {
                throw new IllegalArgumentException("夹具里没有菜单 " + menuCode + "，无法授权 " + code);
            }
            RolePermission rp = new RolePermission();
            rp.setRoleId(role.getId());
            rp.setMenuId(menuId);
            rp.setMenuCode(menuCode);
            rp.setAction(code.substring(idx + 1));
            permissions.add(rp);
        }
        return this;
    }

    /**
     * 定义菜单（权限码的宿主）。
     *
     * <p>id 从 1001 起顺序分配；只定义动作码实际存在的模块，与后端首批接入的范围一致。
     */
    public RbacFixtures menu(String code, String name, String menuType) {
        Menu m = new Menu();
        m.setId(1001L + menus.size());
        m.setCode(code);
        m.setName(name);
        m.setMenuType(menuType);
        m.setStatus(1);
        m.setSort(menus.size() + 1);
        menus.add(m);
        menuIdByCode.put(code, m.getId());
        return this;
    }

    /** 首批接入的 18 个菜单码。 */
    private RbacFixtures defineMenus() {
        menu("asset.ledger", "资产台账", "menu");
        menu("asset.project", "项目管理", "menu");
        menu("asset.structureLog", "权属变更", "menu");
        menu("billing.bill", "账单管理", "menu");
        menu("contract.ledger", "合同台账", "menu");
        menu("contract.vacate", "退租管理", "menu");
        menu("finance.bankFlow", "银行流水", "menu");
        menu("finance.invoice", "发票管理", "menu");
        menu("finance.payment", "付款管理", "menu");
        menu("finance.refund", "退款管理", "menu");
        menu("finance.voucher", "凭证管理", "menu");
        menu("org.company", "公司管理", "menu");
        menu("org.department", "部门管理", "menu");
        menu("org.structure", "组织架构", "menu");
        menu("org.user", "用户管理", "menu");
        menu("system.dict", "数据字典", "menu");
        menu("system.menu", "菜单管理", "menu");
        menu("system.role", "角色权限", "menu");
        return this;
    }

    /** 追加一条数据范围排除（勾选即排除，命中即整棵子树）。 */
    public RbacFixtures exclude(String roleCode, long companyId) {
        RoleDataExclude row = new RoleDataExclude();
        row.setRoleId(role(roleCode).getId());
        row.setCompanyId(companyId);
        excludes.add(row);
        return this;
    }

    public RbacFixtures person(long id, String username, String name, long companyId, long departmentId,
            String roleCode, int status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setName(name);
        u.setCompanyId(companyId);
        u.setDepartmentId(departmentId);
        u.setStatus(status);
        users.add(u);
        bind(id, role(roleCode).getId());
        return this;
    }

    public RbacFixtures bind(long userId, long roleId) {
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        bindings.add(ur);
        return this;
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    public CompanyTreeFixtures org() {
        return org;
    }

    public List<Role> roles() {
        return roles;
    }

    public Role role(String code) {
        return roles.stream()
                .filter(r -> r.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("夹具中没有角色 " + code));
    }

    public User user(String username) {
        return users.stream()
                .filter(u -> u.getUsername().equals(username))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("夹具中没有账号 " + username));
    }

    /** 该角色的权限码集合（夹具视角，用于与装配结果对照）。 */
    public Set<String> grantedCodes(String roleCode) {
        long roleId = role(roleCode).getId();
        return permissions.stream()
                .filter(p -> p.getRoleId() == roleId)
                .map(p -> p.getMenuCode() + ":" + p.getAction())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---------------------------------------------------------------------
    // 装配
    // ---------------------------------------------------------------------

    /** 未切换公司地装配 LoginUser（走真实 {@link RbacService#buildLoginUser}）。 */
    public LoginUser loginUser(String username) {
        return loginUser(username, null);
    }

    /** 带「全局公司切换」地装配 LoginUser；{@code requestedCompanyId} 会被可切换范围校验。 */
    public LoginUser loginUser(String username, Long requestedCompanyId) {
        User user = user(username);
        return newService(user.getId()).buildLoginUser(user, "pc", requestedCompanyId);
    }

    /**
     * 该 LoginUser 的可访问公司范围（{@link RbacService#companyScope}）。
     *
     * <p>刻意不按用户重新桩 mapper：{@code companyScope} 只依赖 {@code LoginUser} 上已装配好的
     * 排除清单与公司树，重桩只会掩盖「装配阶段漏填排除清单」这类缺陷。
     */
    public CompanyScope scopeOf(LoginUser user) {
        return newService(user.getUserId() == null ? -1L : user.getUserId()).companyScope(user);
    }

    /** 面向该账号的 {@link RbacService}（桩已按该账号的角色复刻 SQL 过滤语义）。 */
    public RbacService newService(long userId) {
        List<Long> boundRoleIds = bindings.stream()
                .filter(b -> b.getUserId() == userId)
                .map(UserRole::getRoleId)
                .distinct()
                .toList();
        // 生产里权限与排除的查询条件是 in(enabledRoleIds)：停用角色在 SQL 层就被排除，
        // 因此这里也必须只喂启用角色的行，否则「停用即撤销」会被夹具悄悄放行
        List<Long> enabledRoleIds = roles.stream()
                .filter(r -> boundRoleIds.contains(r.getId()))
                .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                .map(Role::getId)
                .toList();

        when(userRoleMapper.selectList(any())).thenAnswer(invocation ->
                bindings.stream().filter(b -> b.getUserId() == userId).toList());
        when(roleMapper.selectBatchIds(any())).thenAnswer(invocation ->
                roles.stream().filter(r -> boundRoleIds.contains(r.getId())).toList());
        when(rolePermissionMapper.selectList(any())).thenAnswer(invocation ->
                permissions.stream().filter(p -> enabledRoleIds.contains(p.getRoleId())).toList());
        when(roleDataExcludeMapper.selectList(any())).thenAnswer(invocation ->
                excludes.stream().filter(e -> enabledRoleIds.contains(e.getRoleId())).toList());

        return new RbacService(userRoleMapper, roleMapper, rolePermissionMapper, roleDataExcludeMapper,
                redisTemplate, org.treeService());
    }

    // ---------------------------------------------------------------------
    // 角色管理（RoleService）
    // ---------------------------------------------------------------------

    /**
     * 面向角色管理的 {@link RoleService}，与 {@link #newService(long)} 共用同一批 mapper 桩，
     * 因此「调用者的权限」与「角色被写入的权限」在同一份数据上求值。
     *
     * <h2>用例必须自己覆盖的两处桩</h2>
     * Mockito 读不出 {@code LambdaQueryWrapper} 的条件，以下两个计数只能给整体答案，
     * 本夹具默认都返回 {@code 0}（= 无成员 / 非自身角色 / 角色码不重复）：
     * <ul>
     *   <li>{@code userRoleMapper.selectCount} —— {@code assertNotOwnRole()} 与
     *       {@code deleteRole()} 的成员数。若要验证「不能改自己所属角色」，必须
     *       {@code when(f.userRoleMapper().selectCount(any())).thenReturn(1L)}，
     *       并配一条返回 0 的反向对照，否则断言可能因为「本来就返回 0」而通过；</li>
     *   <li>{@code roleMapper.selectCount} —— 角色码唯一性校验。</li>
     * </ul>
     */
    public RoleService newRoleService() {
        return new RoleService(roleMapper, rolePermissionMapper, roleDataExcludeMapper, userRoleMapper,
                menuMapper, menuService, org.treeService(), permissionRegistry);
    }

    public List<Menu> menus() {
        return menus;
    }

    public Long menuId(String code) {
        Long id = menuIdByCode.get(code);
        if (id == null) {
            throw new IllegalArgumentException("夹具里没有菜单 " + code);
        }
        return id;
    }

    /** 已落库的角色权限行（{@code savePermissions} 的写入记录）。 */
    public List<RolePermission> insertedPermissions() {
        return insertedPermissions;
    }

    /** 已落库的排除行（{@code saveDataScope} 的写入记录）。 */
    public List<RoleDataExclude> insertedExcludes() {
        return insertedExcludes;
    }

    /** 已落库的角色（{@code createRole} 的写入记录）。 */
    public List<Role> insertedRoles() {
        return insertedRoles;
    }

    /** 供用例覆盖默认桩（计数类 / 唯一性校验）。 */
    public UserRoleMapper userRoleMapper() {
        return userRoleMapper;
    }

    public RoleMapper roleMapper() {
        return roleMapper;
    }

    public RolePermissionMapper rolePermissionMapper() {
        return rolePermissionMapper;
    }

    public RoleDataExcludeMapper roleDataExcludeMapper() {
        return roleDataExcludeMapper;
    }
}
