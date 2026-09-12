package com.ams.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ams.common.exception.AppException;
import com.ams.support.CompanyTreeFixtures;
import com.ams.support.RbacFixtures;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 角色权限与数据范围的行为锁（设计 5.2 / 5.3、验收 4/5，D15 / D18）。
 *
 * <p>用例全部通过 {@link RbacFixtures} 装配，因此覆盖的是**真实**的
 * {@link RbacService#buildLoginUser} 路径与**真实**的公司树遍历，而不是手搓的 LoginUser。
 *
 * <p>重点锁死「减法算空 / 有排除」这两种情况的收敛方向 —— 它们是本设计里唯一
 * 「算错就静默放大权限」的地方：
 * <ul>
 *   <li>排除清单只减不放开：任何一次扣除都不得让范围退回「不受限」；</li>
 *   <li>{@code dataScope=all} 与 super_admin <strong>不等于</strong>免排除；</li>
 *   <li>停用角色必须同时撤销权限、dataScope 与排除清单。</li>
 * </ul>
 */
class RbacDataScopeTest {

    private static final long GROUP = CompanyTreeFixtures.GROUP;
    private static final long SUB = CompanyTreeFixtures.SUB;
    private static final long COMMERCIAL = CompanyTreeFixtures.COMMERCIAL;
    private static final long PROPERTY = CompanyTreeFixtures.PROPERTY;
    private static final long CULTURE = CompanyTreeFixtures.CULTURE;
    private static final long ANCIENT = CompanyTreeFixtures.ANCIENT;
    private static final long WATER = CompanyTreeFixtures.WATER;
    private static final long OTHER_ROOT = CompanyTreeFixtures.OTHER_ROOT;
    private static final long DISABLED = CompanyTreeFixtures.DISABLED;

    private RbacFixtures f;

    @BeforeEach
    void setUp() {
        f = RbacFixtures.standard();
    }

    // ---------------------------------------------------------------------
    // 基础范围：上级可见下级、无关公司互不可见（验收 4）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("未切换公司：company 范围收敛为「所属公司 + 全部下级子树」")
    void companyScopeIsHomeSubtree() {
        CompanyScope scope = f.scopeOf(f.loginUser(RbacFixtures.USER_LEADER));

        assertThat(scope.isUnrestricted()).isFalse();
        assertThat(scope.ids()).containsExactlyInAnyOrder(SUB, COMMERCIAL, PROPERTY, DISABLED);
        assertThat(scope.allows(WATER)).isFalse();
        assertThat(scope.allows(OTHER_ROOT)).isFalse();
    }

    @Test
    @DisplayName("验收 4 正向：上级公司账号默认可见下级公司的数据")
    void parentCompanySeesSubsidiaryByDefault() {
        CompanyScope scope = f.scopeOf(f.loginUser(RbacFixtures.USER_CHAIRMAN));

        assertThat(scope.allows(SUB)).isTrue();
        assertThat(scope.allows(COMMERCIAL)).isTrue();
        assertThat(scope.allows(ANCIENT)).isTrue();
    }

    @Test
    @DisplayName("验收 4 反向：无隶属关系的公司互不可见（同级、跨树、上级都不可见）")
    void unrelatedCompaniesAreInvisible() {
        CompanyScope scope = f.scopeOf(f.loginUser(RbacFixtures.USER_LEADER));

        assertThat(scope.allows(WATER)).isFalse(); // 同级但无隶属
        assertThat(scope.allows(OTHER_ROOT)).isFalse(); // 另一棵树的根
        assertThat(scope.allows(GROUP)).isFalse(); // 上级公司也看不到
    }

    // ---------------------------------------------------------------------
    // 不受限的边界：all / super_admin 不是免排除
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("未切换且无排除：super_admin 为不受限，列表查询不加 company 条件")
    void superAdminWithoutExclusionsIsUnrestricted() {
        CompanyScope scope = f.scopeOf(f.loginUser(RbacFixtures.USER_ADMIN));

        assertThat(scope.isUnrestricted()).isTrue();
        assertThat(scope.hasFilter()).isFalse();
        assertThat(scope.allows(WATER)).isTrue();
    }

    @Test
    @DisplayName("有排除清单时：super_admin 也必须物化基线再扣除，绝不退回不受限")
    void superAdminWithExclusionsIsNarrowed() {
        RbacFixtures fixture = RbacFixtures.standard()
                .exclude(RbacFixtures.ROLE_SUPER_ADMIN, CULTURE);
        CompanyScope scope = fixture.scopeOf(fixture.loginUser(RbacFixtures.USER_ADMIN));

        assertThat(scope.isUnrestricted()).isFalse();
        assertThat(scope.allows(CULTURE)).isFalse();
        assertThat(scope.allows(ANCIENT)).isFalse(); // 子树整棵扣除
        assertThat(scope.allows(WATER)).isTrue(); // 其余公司不受影响
        assertThat(scope.allows(OTHER_ROOT)).isTrue();
    }

    @Test
    @DisplayName("dataScope=all 的非超管账号：仍受排除清单约束（all ≠ 免排除）")
    void allScopeStillAppliesExclusions() {
        LoginUser steward = f.loginUser(RbacFixtures.USER_GROUP_STEWARD);

        assertThat(steward.isSuperAdmin()).isFalse();
        assertThat(steward.getDataScope()).isEqualTo("all");

        CompanyScope scope = f.scopeOf(steward);
        assertThat(scope.isUnrestricted()).isFalse();
        assertThat(scope.allows(CULTURE)).isFalse();
        assertThat(scope.allows(ANCIENT)).isFalse();
        assertThat(scope.allows(SUB)).isTrue();
    }

    @Test
    @DisplayName("未知 dataScope 取值按最窄处理（朝安全侧收敛，不会被当成 all）")
    void unknownDataScopeFallsBackToNarrowest() {
        RbacFixtures fixture = RbacFixtures.standard()
                .role(11, "weird_role", "未知范围角色", "not_a_scope", 1);
        fixture.person(99, "weirduser", "未知范围", SUB, 201L, "weird_role", 1);
        LoginUser weird = fixture.loginUser("weirduser");

        assertThat(weird.getDataScope()).isEqualTo("self");
        CompanyScope scope = fixture.scopeOf(weird);
        assertThat(scope.isUnrestricted()).isFalse();
        assertThat(scope.allows(SUB)).isTrue();
        assertThat(scope.allows(WATER)).isFalse();
    }

    // ---------------------------------------------------------------------
    // 排除清单：整棵子树 + 算空降级（D15）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("D15：只排除母公司时，装配阶段展开为整棵子树（下级无需单独勾选）")
    void exclusionExpandsToSubtree() {
        RbacFixtures fixture = RbacFixtures.standard()
                .exclude(RbacFixtures.ROLE_FINANCE, CULTURE);
        LoginUser finance = fixture.loginUser(RbacFixtures.USER_FINANCE);

        assertThat(finance.getExcludedCompanyIds()).contains(CULTURE, ANCIENT);
        assertThat(fixture.scopeOf(finance).allows(ANCIENT)).isFalse();
    }

    @Test
    @DisplayName("D15：父公司与子公司在同一角色上都被显式排除时，展开结果仍是整棵子树（不重不漏）")
    void parentAndChildBothExcludedIsIdempotent() {
        LoginUser operator = f.loginUser(RbacFixtures.USER_OPERATOR);

        assertThat(operator.getExcludedCompanyIds())
                .containsExactlyInAnyOrder(CULTURE, ANCIENT);

        CompanyScope scope = f.scopeOf(operator);
        assertThat(scope.allows(CULTURE)).isFalse();
        assertThat(scope.allows(ANCIENT)).isFalse();
        assertThat(scope.allows(SUB)).isTrue();
    }

    @Test
    @DisplayName("排除掉全部可见公司时：降级为拒绝哨兵，绝不退回不受限")
    void excludingEverythingYieldsDeniedNotUnrestricted() {
        // clerk 的基线恰是「所属公司子树」，把所属公司本身排除即可清空基线
        RbacFixtures fixture = RbacFixtures.standard()
                .exclude(RbacFixtures.ROLE_CLERK, SUB);
        CompanyScope scope = fixture.scopeOf(fixture.loginUser(RbacFixtures.USER_CLERK));

        assertThat(scope.isUnrestricted()).isFalse();
        assertThat(scope.isDenied()).isTrue();
        assertThat(scope.allows(SUB)).isFalse();
        assertThat(scope.allows(COMMERCIAL)).isFalse();
    }

    // ---------------------------------------------------------------------
    // 停用角色与多角色
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("停用角色即撤销：不贡献角色码、权限、dataScope 与排除清单")
    void disabledRoleContributesNothing() {
        LoginUser legacy = f.loginUser(RbacFixtures.USER_LEGACY);

        assertThat(legacy.getRoles()).isEmpty();
        assertThat(legacy.getPermissions()).isEmpty();
        assertThat(legacy.getDataScope()).isEqualTo("self");
        assertThat(legacy.getExcludedCompanyIds()).isEmpty();
        assertThat(legacy.hasPermission("asset.ledger:delete")).isFalse();

        // 注意：停用角色撤的是**权限**，不是把账号的数据范围清空 —— 范围仍退化为
        // 「所属公司子树」。拦住访问的是权限断言，不能指望 scope 为空来兜底。
        assertThat(f.scopeOf(legacy).allows(SUB)).isTrue();
    }

    @Test
    @DisplayName("多角色取最宽：dept 与 all 并存时取 all，且排除清单依然生效")
    void widestDataScopeWinsAndStillHonoursExclusions() {
        f.bind(10L, f.role(RbacFixtures.ROLE_DATA_STEWARD).getId()); // clerk(dept) + data_steward(all)
        LoginUser clerk = f.loginUser(RbacFixtures.USER_CLERK);

        assertThat(clerk.getDataScope()).isEqualTo("all");

        CompanyScope scope = f.scopeOf(clerk);
        assertThat(scope.isUnrestricted()).isFalse(); // 两个角色的排除清单合并后非空
        assertThat(scope.allows(PROPERTY)).isFalse(); // clerk 排除的
        assertThat(scope.allows(CULTURE)).isFalse(); // data_steward 排除的
        assertThat(scope.allows(WATER)).isTrue();
    }

    // ---------------------------------------------------------------------
    // 公司切换
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("全局公司切换：切入某公司后连 super_admin 也收窄为「生效公司 + 下级」")
    void switchingNarrowsEvenSuperAdmin() {
        LoginUser switched = f.loginUser(RbacFixtures.USER_ADMIN, SUB);

        assertThat(switched.isCompanyScoped()).isTrue();
        assertThat(switched.getCompanyId()).isEqualTo(SUB);

        CompanyScope scope = f.scopeOf(switched);
        assertThat(scope.isUnrestricted()).isFalse();
        assertThat(scope.allows(COMMERCIAL)).isTrue();
        assertThat(scope.allows(WATER)).isFalse();
    }

    @Test
    @DisplayName("公司切换越界：范围外 / 已停用 / 被排除的目标一律静默回落为所属公司")
    void outOfScopeSwitchFallsBackSilently() {
        LoginUser toDisabled = f.loginUser(RbacFixtures.USER_LEADER, DISABLED);
        assertThat(toDisabled.isCompanyScoped()).isFalse();
        assertThat(toDisabled.getCompanyId()).isEqualTo(SUB);

        LoginUser toUnrelated = f.loginUser(RbacFixtures.USER_LEADER, OTHER_ROOT);
        assertThat(toUnrelated.isCompanyScoped()).isFalse();
        assertThat(toUnrelated.getCompanyId()).isEqualTo(SUB);

        LoginUser toExcluded = f.loginUser(RbacFixtures.USER_OPERATOR, CULTURE);
        assertThat(toExcluded.isCompanyScoped()).isFalse();
        assertThat(toExcluded.getCompanyId()).isEqualTo(SUB);
    }

    @Test
    @DisplayName("可切换范围 = 所属公司子树，且扣除排除子树、排除停用与不存在的公司")
    void switchableRange() {
        RbacService service = f.newService(-1L); // 判定不依赖具体账号的装配结果
        Set<Long> none = Set.of();

        assertThat(service.isSwitchable(SUB, false, SUB, none)).isTrue();
        assertThat(service.isSwitchable(SUB, false, COMMERCIAL, none)).isTrue();
        assertThat(service.isSwitchable(SUB, false, WATER, none)).isFalse();
        assertThat(service.isSwitchable(SUB, false, OTHER_ROOT, none)).isFalse();
        assertThat(service.isSwitchable(SUB, false, DISABLED, none)).isFalse();
        assertThat(service.isSwitchable(SUB, false, 4242L, none)).isFalse();
        assertThat(service.isSwitchable(SUB, false, null, none)).isFalse();

        // 不受限账号可切到与本司无关的树，但仍受排除约束
        assertThat(service.isSwitchable(SUB, true, OTHER_ROOT, none)).isTrue();
        assertThat(service.isSwitchable(SUB, true, CULTURE, Set.of(CULTURE, ANCIENT))).isFalse();
    }

    // ---------------------------------------------------------------------
    // 对象级断言与权限断言
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("对象级断言：归属为空（company_id is null）的记录对受限账号一律拒绝")
    void objectLevelAssertionRejectsUnknownOwnership() {
        RbacService service = f.newService(-1L);
        LoginUser leader = f.loginUser(RbacFixtures.USER_LEADER);

        service.assertCompanyAccess(leader, COMMERCIAL); // 范围内：放行

        assertThatThrownBy(() -> service.assertCompanyAccess(leader, null))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.assertCompanyAccess(leader, WATER))
                .isInstanceOf(AppException.class);

        // 不受限账号不受这两条限制
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);
        service.assertCompanyAccess(admin, WATER);
        service.assertCompanyAccess(admin, null);
    }

    @Test
    @DisplayName("角色权限装配：权限码为 menuCode:action，且只来自已启用角色")
    void permissionsAssembledFromEnabledRolesOnly() {
        LoginUser finance = f.loginUser(RbacFixtures.USER_FINANCE);
        assertThat(finance.getPermissions())
                .contains("finance.payment:update", "billing.bill:create");
        assertThat(finance.hasPermission("finance.payment:update")).isTrue();
        assertThat(finance.hasPermission("asset.ledger:delete")).isFalse();

        // 决策层刻意只读：D18 拒绝「按现有可达能力回填写动作」的对照实现
        LoginUser leader = f.loginUser(RbacFixtures.USER_LEADER);
        assertThat(leader.getPermissions())
                .contains("asset.ledger:view", "contract.ledger:view")
                .noneMatch(p -> p.endsWith(":create")
                        || p.endsWith(":update")
                        || p.endsWith(":delete")
                        || p.endsWith(":approve"));
    }

    @Test
    @DisplayName("super_admin 权限旁路：无 role_permission 行也放行（但数据范围不因此放开）")
    void superAdminPermissionBypass() {
        RbacService service = f.newService(-1L);
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);

        assertThat(admin.permissionSet()).isEmpty();
        assertThat(admin.hasPermission("asset.ledger:delete")).isTrue();
        service.assertPermission(admin, "asset.ledger", "delete");

        assertThatThrownBy(() ->
                service.assertPermission(f.loginUser(RbacFixtures.USER_LEADER), "asset.ledger", "delete"))
                .isInstanceOf(AppException.class);
    }

    // ---------------------------------------------------------------------
    // 口径锁定（改变行为时本用例应随之调整）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("停用公司仍参与数据范围：停用不抹掉历史数据，其名下记录对范围内用户依然可读")
    void disabledCompanyStaysInsideDataScope() {
        // 这是刻意口径，不是缺陷：CompanyTreeService.listCompanies() 不过滤 status，
        // 因此停用公司仍留在上级公司的子树内。（「能不能切进去」由 AuthService /
        // isActiveCompany 单独按 status 把关，与「能不能看到数据」是两件事。）
        // 若哪天决定「停用即隐藏数据」，应当改实现并同步改掉这条用例。
        assertThat(f.org().subtree(SUB)).contains(DISABLED);
        assertThat(f.scopeOf(f.loginUser(RbacFixtures.USER_LEADER)).allows(DISABLED)).isTrue();
    }
}
