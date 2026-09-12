package com.ams.modules.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.system.dto.RolePermissionBatch;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.RoleDataExclude;
import com.ams.platform.security.LoginUser;
import com.ams.support.CompanyTreeFixtures;
import com.ams.support.RbacFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 角色管理的提权约束（设计 4.5）。
 *
 * <p>三条约束都只能用「调用者是个什么样的账号」来验证，因此调用者统一由
 * {@link RbacFixtures#loginUser(String)} 走真实装配产出，而不是手搓 LoginUser —— 手搓会让
 * 「调用者的 dataScope 是 company」这类前提变成测试自己想当然的假设。
 *
 * <h2>为什么每个用例都配一条反向对照</h2>
 * 夹具读不出 MyBatis-Plus wrapper 的条件，{@code selectCount} 只能给整体答案（默认 0）。
 * 只断言「越权被拦」无法区分「约束生效」与「桩恰好让约束通过」，所以凡是依赖计数的用例
 * 都同时给出计数为 0 时**不**报错的对照。
 */
class RoleServiceGuardTest {

    private static final long SUB = CompanyTreeFixtures.SUB;
    private static final long CULTURE = CompanyTreeFixtures.CULTURE;
    private static final long ANCIENT = CompanyTreeFixtures.ANCIENT;
    private static final long DISABLED = CompanyTreeFixtures.DISABLED;

    private static final long ROLE_SUPER_ADMIN_ID = 1L;
    private static final long ROLE_OPERATOR_ID = 2L;
    private static final long ROLE_ASSET_MGR_ID = 3L;

    private RbacFixtures f;
    private RoleService service;

    @BeforeEach
    void setUp() {
        f = RbacFixtures.standard();
        service = f.newRoleService();
    }

    // ---------------------------------------------------------------------
    // 约束①：只能授予自己已持有的动作
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("约束①：不能授予自己未持有的动作，且校验失败时整体不落库")
    void cannotGrantWhatCallerDoesNotHold() {
        // 运营管理员持有 asset.ledger:view，但没有 delete
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR);
        RolePermissionBatch batch = batch(item(f.menuId("asset.ledger"), "view", "delete"));

        assertThatThrownBy(() -> service.savePermissions(ROLE_ASSET_MGR_ID, batch, caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能授予自己未持有的权限");

        // 「先整体校验再落库」：绝不能出现「删了旧权限、只写了半截新权限」的中间态
        verify(f.rolePermissionMapper(), never()).delete(any());
        assertThat(f.insertedPermissions()).isEmpty();
    }

    @Test
    @DisplayName("约束①反向对照：持有全部动作的 super_admin 可以授予任意动作")
    void superAdminCanGrantAnything() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);
        RolePermissionBatch batch = batch(
                item(f.menuId("system.role"), "assign", "delete", "view"),
                item(f.menuId("asset.ledger"), "delete"));

        service.savePermissions(ROLE_ASSET_MGR_ID, batch, admin);

        assertThat(f.insertedPermissions()).hasSize(4);
    }

    @Test
    @DisplayName("约束①：持有子集的部分动作时，子集内放行、子集外被拦")
    void partialGrantIsCheckedPerAction() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_ASSET_MGR); // 持有 asset.ledger 的 view/create/update/delete

        service.savePermissions(ROLE_ASSET_MGR_ID,
                batch(item(f.menuId("asset.ledger"), "view", "create", "update", "delete")), caller);
        assertThat(f.insertedPermissions()).hasSize(4);

        f.insertedPermissions().clear();
        assertThatThrownBy(() -> service.savePermissions(ROLE_ASSET_MGR_ID,
                batch(item(f.menuId("system.menu"), "delete")), caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能授予自己未持有的权限");
    }

    @Test
    @DisplayName("动作词表：词表外的动作直接拒绝（先于提权校验）")
    void rejectsActionOutsideVocabulary() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);

        assertThatThrownBy(() -> service.savePermissions(ROLE_ASSET_MGR_ID,
                batch(item(f.menuId("asset.ledger"), "view", "publish")), admin))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("动作不在词表");
        assertThat(f.insertedPermissions()).isEmpty();
    }

    @Test
    @DisplayName("菜单必须存在：提交不存在的 menuId 时拒绝，避免写出孤立权限行")
    void rejectsUnknownMenu() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);

        assertThatThrownBy(() -> service.savePermissions(ROLE_ASSET_MGR_ID,
                batch(item(999_999L, "view")), admin))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("菜单不存在");
    }

    @Test
    @DisplayName("menuCode 由 menuId 解析回填（请求体里没有该字段，客户端无法指定判 B 显示 A）")
    void menuCodeIsResolvedFromMenuId() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);

        service.savePermissions(ROLE_ASSET_MGR_ID,
                batch(item(f.menuId("finance.payment"), "view", "update")), admin);

        assertThat(f.insertedPermissions())
                .hasSize(2)
                .allSatisfy(rp -> {
                    assertThat(rp.getMenuCode()).isEqualTo("finance.payment");
                    assertThat(rp.getMenuId()).isEqualTo(f.menuId("finance.payment"));
                    assertThat(rp.getRoleId()).isEqualTo(ROLE_ASSET_MGR_ID);
                });
    }

    @Test
    @DisplayName("同一 (menu, action) 重复提交只落一行")
    void duplicateItemsAreDeduplicated() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);

        service.savePermissions(ROLE_ASSET_MGR_ID, batch(
                item(f.menuId("asset.ledger"), "view", "view"),
                item(f.menuId("asset.ledger"), "view")), admin);

        assertThat(f.insertedPermissions()).hasSize(1);
    }

    @Test
    @DisplayName("空批次（清空权限）不报错，只删除旧行")
    void emptyBatchClearsPermissions() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);

        service.savePermissions(ROLE_ASSET_MGR_ID, batch(), admin);

        assertThat(f.insertedPermissions()).isEmpty();
        verify(f.rolePermissionMapper()).delete(any());
    }

    // ---------------------------------------------------------------------
    // 约束②：不得修改已分配给自己的角色
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("约束②：不能修改自己所属角色的权限，也不能改它的数据范围")
    void cannotModifyOwnRole() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR); // 绑定 operator 角色
        when(f.userRoleMapper().selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.savePermissions(ROLE_OPERATOR_ID,
                batch(item(f.menuId("asset.ledger"), "view")), caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能修改自己所属角色");

        assertThatThrownBy(() -> service.saveDataScope(ROLE_OPERATOR_ID, List.of(CULTURE), caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能修改自己所属角色");

        verify(f.rolePermissionMapper(), never()).delete(any());
        assertThat(f.insertedExcludes()).isEmpty();
    }

    @Test
    @DisplayName("约束②反向对照：不是自己的角色时可以改（用 0 计数证明这条判断真的由绑定决定）")
    void canModifyRoleNotBoundToCaller() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR);
        when(f.userRoleMapper().selectCount(any())).thenReturn(0L);

        service.savePermissions(ROLE_ASSET_MGR_ID,
                batch(item(f.menuId("asset.ledger"), "view")), caller);
        service.saveDataScope(ROLE_ASSET_MGR_ID, List.of(CULTURE), caller);

        assertThat(f.insertedPermissions()).hasSize(1);
        assertThat(f.insertedExcludes()).hasSize(1);
    }

    @Test
    @DisplayName("约束②：super_admin 是唯一例外（首次授权引导需要它改任意角色）")
    void superAdminMayModifyOwnRole() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN); // 绑定 super_admin 角色
        when(f.userRoleMapper().selectCount(any())).thenReturn(1L);

        service.savePermissions(ROLE_SUPER_ADMIN_ID,
                batch(item(f.menuId("asset.ledger"), "view")), admin);

        assertThat(f.insertedPermissions()).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // 约束③：写入的 dataScope 不得宽于自身
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("约束③：dataScope=company 的调用者不能创建或改出 all 的角色（提权口子）")
    void cannotWidenDataScopeBeyondOwn() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR); // dataScope=company
        assertThat(caller.getDataScope()).isEqualTo("company");

        Role created = role("wide_role", "过宽角色", "all");
        assertThatThrownBy(() -> service.createRole(created, caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能授予宽于自身的数据范围");
        assertThat(f.insertedRoles()).isEmpty();

        Role updated = new Role();
        updated.setDataScope("all");
        assertThatThrownBy(() -> service.updateRole(ROLE_ASSET_MGR_ID, updated, caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能授予宽于自身的数据范围");
    }

    @Test
    @DisplayName("约束③：同宽与更窄允许，null 视为未指定不拦（表单只改名称时不该报错）")
    void sameOrNarrowerDataScopeIsAllowed() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR);

        service.createRole(role("same_scope", "同宽角色", "company"), caller);
        service.createRole(role("narrow_scope", "更窄角色", "dept"), caller);
        service.createRole(role("null_scope", "未指定范围", null), caller);

        assertThat(f.insertedRoles()).hasSize(3);
    }

    @Test
    @DisplayName("约束③：super_admin 可以创建 all 角色")
    void superAdminMayCreateAllScopeRole() {
        service.createRole(role("wide_role", "全局角色", "all"), f.loginUser(RbacFixtures.USER_ADMIN));

        assertThat(f.insertedRoles()).hasSize(1);
        assertThat(f.insertedRoles().get(0).getStatus()).isEqualTo(1); // 未指定时默认启用
    }

    // ---------------------------------------------------------------------
    // createRole 的基础校验
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("创建角色：编码与名称必填，编码不得重复")
    void createRoleValidatesInput() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);

        assertThatThrownBy(() -> service.createRole(role(null, "无名", "company"), admin))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("角色编码不能为空");

        assertThatThrownBy(() -> service.createRole(role("code_only", null, "company"), admin))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("角色名称不能为空");

        when(f.roleMapper().selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.createRole(role("operator", "重复", "company"), admin))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("角色编码已存在");
    }

    // ---------------------------------------------------------------------
    // saveDataScope
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("排除清单：不能排除自己所属公司（否则会把自己锁在门外）")
    void cannotExcludeOwnCompany() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR); // 所属公司 SUB

        assertThatThrownBy(() -> service.saveDataScope(ROLE_ASSET_MGR_ID, List.of(SUB), caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不能排除自己所属公司");

        assertThat(f.insertedExcludes()).isEmpty();
    }

    @Test
    @DisplayName("排除清单：不存在或已停用的公司拒绝写入")
    void rejectsInactiveOrUnknownCompany() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR);

        assertThatThrownBy(() -> service.saveDataScope(ROLE_ASSET_MGR_ID, List.of(DISABLED), caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("公司不存在或已停用");

        assertThatThrownBy(() -> service.saveDataScope(ROLE_ASSET_MGR_ID, List.of(4242L), caller))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("公司不存在或已停用");

        assertThat(f.insertedExcludes()).isEmpty();
    }

    @Test
    @DisplayName("排除清单：合法公司写入并去重（父与子可同时显式排除，展开由装配阶段负责）")
    void savesExclusionsAndDeduplicates() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR);

        service.saveDataScope(ROLE_ASSET_MGR_ID, List.of(CULTURE, ANCIENT, CULTURE), caller);

        assertThat(f.insertedExcludes())
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getRoleId()).isEqualTo(ROLE_ASSET_MGR_ID));
        assertThat(f.insertedExcludes()).extracting(RoleDataExclude::getCompanyId)
                .containsExactlyInAnyOrder(CULTURE, ANCIENT);
        verify(f.roleDataExcludeMapper()).delete(any());
    }

    @Test
    @DisplayName("排除清单：空清单表示清空排除，只删不写")
    void emptyExclusionsClearTheList() {
        LoginUser caller = f.loginUser(RbacFixtures.USER_OPERATOR);

        service.saveDataScope(ROLE_ASSET_MGR_ID, List.of(), caller);

        assertThat(f.insertedExcludes()).isEmpty();
        verify(f.roleDataExcludeMapper()).delete(any());
    }

    // ---------------------------------------------------------------------
    // deleteRole
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("删除角色：仍有成员时拒绝，且不清理权限与排除行")
    void deleteRejectsWhenMembersExist() {
        when(f.userRoleMapper().selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteRole(ROLE_ASSET_MGR_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("仍有 2 名成员");

        verify(f.rolePermissionMapper(), never()).delete(any());
        verify(f.roleDataExcludeMapper(), never()).delete(any());
        verify(f.roleMapper(), never()).deleteById(ROLE_ASSET_MGR_ID);
    }

    @Test
    @DisplayName("删除角色：无成员时连同权限与排除行一起清理（避免孤儿行）")
    void deleteCleansUpRelatedRows() {
        when(f.userRoleMapper().selectCount(any())).thenReturn(0L);

        service.deleteRole(ROLE_ASSET_MGR_ID);

        verify(f.rolePermissionMapper()).delete(any());
        verify(f.roleDataExcludeMapper()).delete(any());
        verify(f.roleMapper()).deleteById(ROLE_ASSET_MGR_ID);
    }

    @Test
    @DisplayName("角色不存在：所有写接口都以 NOT_FOUND 拒绝")
    void unknownRoleIsNotFound() {
        LoginUser admin = f.loginUser(RbacFixtures.USER_ADMIN);

        assertThatThrownBy(() -> service.savePermissions(4242L, batch(), admin))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("角色不存在");
        assertThatThrownBy(() -> service.saveDataScope(4242L, List.of(), admin))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("角色不存在");
        assertThatThrownBy(() -> service.deleteRole(4242L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("角色不存在");
    }

    // ---------------------------------------------------------------------
    // 构造辅助
    // ---------------------------------------------------------------------

    private static Role role(String code, String name, String dataScope) {
        Role role = new Role();
        role.setCode(code);
        role.setName(name);
        role.setDataScope(dataScope);
        return role;
    }

    private static RolePermissionBatch batch(RolePermissionBatch.Item... items) {
        RolePermissionBatch batch = new RolePermissionBatch();
        batch.setItems(List.of(items));
        return batch;
    }

    private static RolePermissionBatch.Item item(Long menuId, String... actions) {
        RolePermissionBatch.Item item = new RolePermissionBatch.Item();
        item.setMenuId(menuId);
        item.setActions(List.of(actions));
        return item;
    }
}
