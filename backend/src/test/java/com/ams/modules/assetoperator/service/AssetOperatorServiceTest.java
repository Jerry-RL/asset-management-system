package com.ams.modules.assetoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.assetoperator.dto.AssetOperatorInput;
import com.ams.modules.assetoperator.dto.AssetOperatorScopeRef;
import com.ams.modules.assetoperator.dto.AssetOperatorView;
import com.ams.modules.assetoperator.entity.AssetOperator;
import com.ams.modules.assetoperator.entity.AssetOperatorRole;
import com.ams.modules.assetoperator.entity.AssetOperatorScope;
import com.ams.modules.assetoperator.mapper.AssetOperatorMapper;
import com.ams.modules.assetoperator.mapper.AssetOperatorRoleMapper;
import com.ams.modules.assetoperator.mapper.AssetOperatorScopeMapper;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.mapper.RoleMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 资产运营人员管理的校验与落库口径（V60）。
 *
 * <p>钉死五条**在页面上看不出来、错一次就长期错**的规则：
 *
 * <ol>
 *   <li>一个人员只能有一份**有效**档案（并发由部分唯一索引兜底，可读原因由服务层给出）；</li>
 *   <li>范围去重必须按 {@code (type, id)} —— 裸 id 去重会把 {@code project:3} 与
 *       {@code asset:3} 吞掉一条；</li>
 *   <li>停用角色不得登记进档案（否则会让人以为那个人还在承担该职责）；</li>
 *   <li>已退出的资产不得登记为运营范围；</li>
 *   <li>角色与范围都是**全量替换**：编辑一次 → 先删后插，不产生残留行。</li>
 * </ol>
 *
 * <p>租户与角色只登记不入鉴权（不写 {@code user_role}）由迁移契约测试守着 ——
 * 那是 SQL 层面的约束，服务层本来就没有写它的代码路径。
 */
class AssetOperatorServiceTest {

    private static final long OPERATOR_ID = 11L;
    private static final long USER_ID = 21L;
    private static final long ROLE_ID = 31L;
    private static final long OTHER_ROLE_ID = 32L;
    private static final long PROJECT_ID = 41L;
    private static final long ZONE_ID = 42L;
    private static final long ASSET_ID = 43L;
    private static final long COMPANY_ID = 51L;

    private final AssetOperatorMapper operatorMapper = mock(AssetOperatorMapper.class);
    private final AssetOperatorRoleMapper operatorRoleMapper = mock(AssetOperatorRoleMapper.class);
    private final AssetOperatorScopeMapper operatorScopeMapper = mock(AssetOperatorScopeMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final CompanyMapper companyMapper = mock(CompanyMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ProjectZoneMapper projectZoneMapper = mock(ProjectZoneMapper.class);
    private final AssetMapper assetMapper = mock(AssetMapper.class);

    private final AssetOperatorService service = new AssetOperatorService(
            operatorMapper,
            operatorRoleMapper,
            operatorScopeMapper,
            userMapper,
            roleMapper,
            companyMapper,
            departmentMapper,
            projectMapper,
            projectZoneMapper,
            assetMapper);

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private User enabledUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setName("张三");
        user.setPhone("13800000000");
        user.setCompanyId(COMPANY_ID);
        user.setStatus(1);
        return user;
    }

    private Role role(long id, String name, int status) {
        Role role = new Role();
        role.setId(id);
        role.setName(name);
        role.setStatus(status);
        return role;
    }

    private AssetOperatorScopeRef scope(String type, long id) {
        AssetOperatorScopeRef ref = new AssetOperatorScopeRef();
        ref.setScopeType(type);
        ref.setScopeId(id);
        return ref;
    }

    private AssetOperatorInput input(List<AssetOperatorScopeRef> scopes) {
        AssetOperatorInput input = new AssetOperatorInput();
        input.setUserId(USER_ID);
        input.setRoleIds(new ArrayList<>(List.of(ROLE_ID)));
        input.setScopes(new ArrayList<>(scopes));
        return input;
    }

    /** 桩：人员 / 角色 / 三种标的都可用，且当前没有同人的档案。 */
    private void stubValid() {
        when(userMapper.selectById(USER_ID)).thenReturn(enabledUser());
        when(roleMapper.selectBatchIds(any()))
                .thenReturn(List.of(role(ROLE_ID, "运营管理员", 1), role(OTHER_ROLE_ID, "资产管理员", 1)));
        when(operatorMapper.selectCount(any())).thenReturn(0L);
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("洪泽湖畔商业综合体");
        project.setCompanyId(COMPANY_ID);
        // 用 thenAnswer 回显被请求的 id：去重用例需要「同一 id 落在不同类型上」，
        // 写死 PROJECT_ID 会让那条断言因为「标的不存在」而不是因为去重生效才通过
        when(projectMapper.selectOne(any())).thenAnswer(invocation -> {
            Project echo = new Project();
            echo.setId(project.getId());
            echo.setName(project.getName());
            echo.setCompanyId(COMPANY_ID);
            return echo;
        });
        ProjectZone zone = new ProjectZone();
        zone.setId(ZONE_ID);
        zone.setProjectId(PROJECT_ID);
        zone.setName("湖滨商务区");
        when(projectZoneMapper.selectActiveById(any())).thenAnswer(invocation -> {
            ProjectZone echo = new ProjectZone();
            echo.setId(invocation.getArgument(0));
            echo.setProjectId(PROJECT_ID);
            echo.setName(zone.getName());
            return echo;
        });
        when(assetMapper.selectById(any())).thenAnswer(invocation -> {
            Asset echo = new Asset();
            echo.setId(invocation.getArgument(0));
            echo.setName("商务大厦 1F");
            echo.setProjectId(PROJECT_ID);
            return echo;
        });
        doAnswer(invocation -> {
            ((AssetOperator) invocation.getArgument(0)).setId(OPERATOR_ID);
            return 1;
        }).when(operatorMapper).insert(any(AssetOperator.class));
    }

    /** 读回详情必需的桩（create 末尾会调 get）。 */
    private void stubReadBack() {
        AssetOperator row = new AssetOperator();
        row.setId(OPERATOR_ID);
        row.setUserId(USER_ID);
        row.setStatus(AssetOperator.STATUS_ENABLED);
        when(operatorMapper.selectById(OPERATOR_ID)).thenReturn(row);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(enabledUser()));
        when(operatorRoleMapper.selectList(any())).thenReturn(List.of());
        when(operatorScopeMapper.selectList(any())).thenReturn(List.of());
        // 两个角色都要在：编辑用例会同时提交 ROLE_ID 与 OTHER_ROLE_ID，
        // 只桩一个会让它因为「角色不存在」失败，而那不是被测行为
        when(roleMapper.selectBatchIds(any()))
                .thenReturn(List.of(role(ROLE_ID, "运营管理员", 1),
                        role(OTHER_ROLE_ID, "资产管理员", 1)));
    }

    // ------------------------------------------------------------------
    // 新增
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新增：三样都合法时落库，角色与范围按去重后的集合写入")
    void createWritesRolesAndScopes() {
        stubValid();
        stubReadBack();

        service.create(input(List.of(
                scope(AssetOperatorScope.TYPE_PROJECT, PROJECT_ID),
                scope(AssetOperatorScope.TYPE_ZONE, ZONE_ID),
                scope(AssetOperatorScope.TYPE_ASSET, ASSET_ID))));

        ArgumentCaptor<AssetOperator> saved = ArgumentCaptor.forClass(AssetOperator.class);
        verify(operatorMapper).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo(AssetOperator.STATUS_ENABLED);

        ArgumentCaptor<AssetOperatorScope> scopes =
                ArgumentCaptor.forClass(AssetOperatorScope.class);
        verify(operatorScopeMapper, org.mockito.Mockito.times(3)).insert(scopes.capture());
        assertThat(scopes.getAllValues()).extracting(AssetOperatorScope::getScopeType)
                .containsExactly(AssetOperatorScope.TYPE_PROJECT, AssetOperatorScope.TYPE_ZONE,
                        AssetOperatorScope.TYPE_ASSET);

        ArgumentCaptor<AssetOperatorRole> roles =
                ArgumentCaptor.forClass(AssetOperatorRole.class);
        verify(operatorRoleMapper).insert(roles.capture());
        assertThat(roles.getValue().getRoleId()).isEqualTo(ROLE_ID);
    }

    @Test
    @DisplayName("范围去重按 (类型, id)：project:3 与 asset:3 是两条范围，不能被裸 id 去重吃掉")
    void scopeDedupeUsesTypeAndId() {
        stubValid();
        stubReadBack();

        service.create(input(List.of(
                scope(AssetOperatorScope.TYPE_PROJECT, 3L),
                scope(AssetOperatorScope.TYPE_ASSET, 3L),
                // 完全重复的一条要被去掉
                scope(AssetOperatorScope.TYPE_PROJECT, 3L))));

        ArgumentCaptor<AssetOperatorScope> scopes =
                ArgumentCaptor.forClass(AssetOperatorScope.class);
        verify(operatorScopeMapper, org.mockito.Mockito.times(2)).insert(scopes.capture());
        assertThat(scopes.getAllValues()).extracting(AssetOperatorScope::getScopeType)
                .containsExactly(AssetOperatorScope.TYPE_PROJECT, AssetOperatorScope.TYPE_ASSET);
    }

    @Test
    @DisplayName("同一人员已有有效档案 -> 冲突，且不落库")
    void rejectsDuplicateArchive() {
        stubValid();
        when(operatorMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(
                input(List.of(scope(AssetOperatorScope.TYPE_PROJECT, PROJECT_ID)))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已有资产运营人员档案");

        verify(operatorMapper, never()).insert(any(AssetOperator.class));
    }

    @Test
    @DisplayName("角色为空 -> 400；停用角色 -> 400（登记停用角色会让人以为职责还在）")
    void rejectsMissingOrDisabledRole() {
        stubValid();
        AssetOperatorInput noRole = input(List.of(scope(AssetOperatorScope.TYPE_PROJECT, PROJECT_ID)));
        noRole.setRoleIds(new ArrayList<>());

        assertThatThrownBy(() -> service.create(noRole))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("至少选择一个角色");

        when(roleMapper.selectBatchIds(any()))
                .thenReturn(List.of(role(ROLE_ID, "已停用角色", 0)));
        assertThatThrownBy(() -> service.create(
                input(List.of(scope(AssetOperatorScope.TYPE_PROJECT, PROJECT_ID)))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("角色已停用");
    }

    @Test
    @DisplayName("范围为空 -> 400；缺少类型或 id -> 400")
    void rejectsMissingScope() {
        stubValid();

        assertThatThrownBy(() -> service.create(input(List.of())))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("至少选择一个资产运营范围");

        AssetOperatorScopeRef incomplete = new AssetOperatorScopeRef();
        incomplete.setScopeType(AssetOperatorScope.TYPE_PROJECT);
        assertThatThrownBy(() -> service.create(input(List.of(incomplete))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("必须同时给出类型与 id");
    }

    @Test
    @DisplayName("已退出的资产不得登记为运营范围")
    void rejectsExitedAsset() {
        stubValid();
        Asset exited = new Asset();
        exited.setId(ASSET_ID);
        exited.setLifecycleStatus("exited");
        when(assetMapper.selectById(ASSET_ID)).thenReturn(exited);

        assertThatThrownBy(() -> service.create(
                input(List.of(scope(AssetOperatorScope.TYPE_ASSET, ASSET_ID)))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("资产已退出");
    }

    @Test
    @DisplayName("分区所属项目不存在 -> 400（否则范围会挂到一个孤儿标签上）")
    void rejectsZoneWithoutLiveProject() {
        stubValid();
        when(projectMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(
                input(List.of(scope(AssetOperatorScope.TYPE_ZONE, ZONE_ID)))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区所属项目不存在或已删除");
    }

    @Test
    @DisplayName("非法范围类型 -> 400（不静默当成资产）")
    void rejectsUnknownScopeType() {
        stubValid();

        assertThatThrownBy(() -> service.create(input(List.of(scope("building", PROJECT_ID)))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("范围类型只能是 project / zone / asset");
    }

    @Test
    @DisplayName("停用人员不得新增档案（否则会登记一个已经不能用的人）")
    void rejectsDisabledUser() {
        stubValid();
        User disabled = enabledUser();
        disabled.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(disabled);

        assertThatThrownBy(() -> service.create(
                input(List.of(scope(AssetOperatorScope.TYPE_PROJECT, PROJECT_ID)))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("人员已停用");
    }

    // ------------------------------------------------------------------
    // 编辑 / 停用 / 删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("编辑：角色与范围都是全量替换（先删后插），不产生残留行")
    void updateReplacesDetailsWholesale() {
        stubValid();
        stubReadBack();
        AssetOperator existing = new AssetOperator();
        existing.setId(OPERATOR_ID);
        existing.setUserId(USER_ID);
        existing.setStatus(AssetOperator.STATUS_ENABLED);
        when(operatorMapper.selectById(OPERATOR_ID)).thenReturn(existing);

        AssetOperatorInput input = input(List.of(scope(AssetOperatorScope.TYPE_ASSET, ASSET_ID)));
        input.setRoleIds(new ArrayList<>(List.of(ROLE_ID, OTHER_ROLE_ID)));
        service.update(OPERATOR_ID, input);

        verify(operatorRoleMapper).delete(any());
        verify(operatorScopeMapper).delete(any());
        verify(operatorScopeMapper).insert(any(AssetOperatorScope.class));
        verify(operatorRoleMapper, org.mockito.Mockito.times(2))
                .insert(any(AssetOperatorRole.class));
    }

    @Test
    @DisplayName("停用：保留档案（软删才清空可见性），状态写 0")
    void disableKeepsArchive() {
        AssetOperator existing = new AssetOperator();
        existing.setId(OPERATOR_ID);
        existing.setUserId(USER_ID);
        existing.setStatus(AssetOperator.STATUS_ENABLED);
        when(operatorMapper.selectById(OPERATOR_ID)).thenReturn(existing);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(enabledUser()));
        when(operatorRoleMapper.selectList(any())).thenReturn(List.of());
        when(operatorScopeMapper.selectList(any())).thenReturn(List.of());

        service.updateStatus(OPERATOR_ID, AssetOperator.STATUS_DISABLED);

        assertThat(existing.getStatus()).isEqualTo(AssetOperator.STATUS_DISABLED);
        verify(operatorMapper).updateById(existing);
        // 停用不是删除：detail 行原样保留
        verify(operatorScopeMapper, never()).delete(any());
    }

    @Test
    @DisplayName("状态取值非法 -> 400（避免把任意数字写进状态列）")
    void rejectsInvalidStatus() {
        assertThatThrownBy(() -> service.updateStatus(OPERATOR_ID, 7))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("状态取值只能是 1（启用）或 0（停用）");

        verify(operatorMapper, never()).updateById(any(AssetOperator.class));
    }

    @Test
    @DisplayName("删除是软删：写 deleted_at 而不是物理删（明细行保留，档案才可追溯）")
    void deleteIsSoft() {
        AssetOperator existing = new AssetOperator();
        existing.setId(OPERATOR_ID);
        existing.setUserId(USER_ID);
        when(operatorMapper.selectById(OPERATOR_ID)).thenReturn(existing);

        service.delete(OPERATOR_ID);

        ArgumentCaptor<AssetOperator> saved = ArgumentCaptor.forClass(AssetOperator.class);
        verify(operatorMapper).updateById(saved.capture());
        assertThat(saved.getValue().getDeletedAt()).isNotNull();
        verify(operatorMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("已软删的档案读不到（详情 404）")
    void softDeletedIsNotFound() {
        AssetOperator deleted = new AssetOperator();
        deleted.setId(OPERATOR_ID);
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(operatorMapper.selectById(OPERATOR_ID)).thenReturn(deleted);

        assertThatThrownBy(() -> service.get(OPERATOR_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("档案不存在");
    }

    // ------------------------------------------------------------------
    // 选项端点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("范围下拉：缺公司直接 400（无公司就没有候选口径）")
    void scopeOptionsNeedsCompany() {
        assertThatThrownBy(() -> service.scopeOptions(
                AssetOperatorScope.TYPE_PROJECT, null, null, null, 1, 50))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请先选择所属公司");
    }

    @Test
    @DisplayName("范围下拉：公司下没有项目时，分区候选直接返回空而不去查分区表")
    void zoneOptionsShortCircuitsWithoutProject() {
        when(projectMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.scopeOptions(AssetOperatorScope.TYPE_ZONE, COMPANY_ID, null, null, 1, 50)
                .getList()).isEmpty();
        verify(projectZoneMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("详情：范围名缺失时给出「已删除标的 #id」而不是空白")
    void scopeNameFallsBackToPlaceholder() {
        AssetOperator row = new AssetOperator();
        row.setId(OPERATOR_ID);
        row.setUserId(USER_ID);
        when(operatorMapper.selectById(OPERATOR_ID)).thenReturn(row);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(enabledUser()));
        when(operatorRoleMapper.selectList(any())).thenReturn(List.of());
        AssetOperatorScope scope = new AssetOperatorScope();
        scope.setOperatorId(OPERATOR_ID);
        scope.setScopeType(AssetOperatorScope.TYPE_PROJECT);
        scope.setScopeId(PROJECT_ID);
        when(operatorScopeMapper.selectList(any())).thenReturn(List.of(scope));
        // 标的名查不到（例如标的已被硬删）
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of());

        AssetOperatorView view = service.get(OPERATOR_ID);

        assertThat(view.getScopes()).hasSize(1);
        assertThat(view.getScopes().get(0).getScopeName())
                .as("空白标签会让读者无法判断是「没填」还是「标的没了」")
                .isEqualTo("已删除标的 #" + PROJECT_ID);
    }
}
