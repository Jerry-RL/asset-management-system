package com.ams.modules.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.dto.ProjectSaveRequest;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.asset.service.AssetQrService;
import com.ams.modules.asset.service.AssetService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.BillPaymentMapper;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.service.RecordPresenceChecker;
import com.ams.platform.security.RbacService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 分区 CRUD 服务层测试（设计 docs/superpowers/specs/2026-09-12-project-list-zone-expand-design.md）。
 *
 * <p>覆盖三条最容易写错的语义：
 * <ol>
 *   <li>归属一律由路径参数决定，请求体里的 {@code id} / {@code projectId} 必须被忽略（防跨项目写入）；</li>
 *   <li>新增时排序缺省 = 当前最大排序 + 1（追加到末尾），而不是 0；</li>
 *   <li>删除挂资产的分区必须被拒；有后续记录的分区同样被拒（两条理由分开报）。</li>
 * </ol>
 *
 * <p>删除语义（设计 §7.3）：`deleteProjectZone`、`replaceZones`、`deleteProject` 共用同一个可删性守卫
 * （`assertZoneRemovable`）。分区自身的两条删除路径是**软删**（写 {@code deleted_at}）；
 * `deleteProject` 在守卫确认整棵子树为空壳后保留硬删 —— 硬删若**不过守卫**才会让分区的后续记录变成悬空数据。
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceZoneTest {

    private static final long PROJECT_ID = 1L;
    private static final long ZONE_ID = 9L;

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectZoneMapper projectZoneMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private AssetQrService assetQrService;
    @Mock
    private CompanyTreeService companyTreeService;
    @Mock
    private CompanyMapper companyMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private RbacService rbacService;
    @Mock
    private BillMapper billMapper;
    @Mock
    private BillPaymentMapper billPaymentMapper;
    @Mock
    private AssetUnitService assetUnitService;
    @Mock
    private RecordPresenceChecker recordPresenceChecker;

    @InjectMocks
    private AssetService service;

    @Test
    @DisplayName("新增分区：排序缺省取当前最大排序 + 1，归属与 id 均由服务端决定")
    void createZoneAppendsToEndAndIgnoresBodyIdentity() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectList(any())).thenReturn(List.of(zone(1L, 0), zone(2L, 4)));
        when(projectZoneMapper.insert(any(ProjectZone.class))).thenReturn(1);

        ProjectZone created = service.createProjectZone(PROJECT_ID, inputZone("C区"));

        assertThat(created.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(created.getName()).isEqualTo("C区");
        assertThat(created.getSort()).isEqualTo(5);
        assertThat(created.getAssetCount()).isZero();
        assertThat(created.getAssetArea()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("新增分区：该项目还没有分区时排序从 0 开始")
    void createZoneStartsAtZeroWhenProjectHasNoZone() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectList(any())).thenReturn(List.of());
        when(projectZoneMapper.insert(any(ProjectZone.class))).thenReturn(1);

        ProjectZone created = service.createProjectZone(PROJECT_ID, inputZone("A区"));

        assertThat(created.getSort()).isZero();
    }

    @Test
    @DisplayName("新增分区：分区名称为空白时拒绝，且不写库")
    void createZoneRejectsBlankName() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));

        assertThatThrownBy(() -> service.createProjectZone(PROJECT_ID, inputZone("   ")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区名称");
        verify(projectZoneMapper, never()).insert(any(ProjectZone.class));
    }

    @Test
    @DisplayName("编辑分区：编号与归属保持路径参数值，请求体 id/projectId 被忽略")
    void updateZoneKeepsIdentityFromPath() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        ProjectZone existing = zone(ZONE_ID, 2);
        existing.setCode("A");
        existing.setRemark("旧备注");
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(existing);
        when(assetMapper.selectMaps(any())).thenReturn(List.of());
        when(projectZoneMapper.updateById(any(ProjectZone.class))).thenReturn(1);

        ProjectZone input = inputZone("A区(新)");
        input.setId(888L);
        input.setCode("A1");
        input.setSort(7);
        input.setRemark("新备注");

        ProjectZone updated = service.updateProjectZone(PROJECT_ID, ZONE_ID, input);

        assertThat(updated.getId()).isEqualTo(ZONE_ID);
        assertThat(updated.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(updated.getName()).isEqualTo("A区(新)");
        assertThat(updated.getCode()).isEqualTo("A1");
        assertThat(updated.getSort()).isEqualTo(7);
        assertThat(updated.getRemark()).isEqualTo("新备注");
    }

    @Test
    @DisplayName("编辑分区：排序为 null 时保留原排序")
    void updateZoneKeepsSortWhenNull() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        ProjectZone existing = zone(ZONE_ID, 3);
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(existing);
        when(assetMapper.selectMaps(any())).thenReturn(List.of());
        when(projectZoneMapper.updateById(any(ProjectZone.class))).thenReturn(1);

        ProjectZone input = inputZone("A区");
        input.setSort(null);

        assertThat(service.updateProjectZone(PROJECT_ID, ZONE_ID, input).getSort()).isEqualTo(3);
    }

    @Test
    @DisplayName("分区不属于该项目时拒绝（防跨项目写入）")
    void zoneOfAnotherProjectIsRejected() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        // 归属与存在性合并成一次 SQL：分区属于别的项目时查不到，所以不会再在 Java 侧比对 projectId
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteProjectZone(PROJECT_ID, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区不存在");
        verify(projectZoneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除分区：分区下有资产时拒绝并提示数量，且不写库")
    void deleteZoneWithAssetsIsRejected() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(zone(ZONE_ID, 1));
        when(assetMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.deleteProjectZone(PROJECT_ID, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("3 项资产");
        verify(projectZoneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除分区：无资产无记录时软删（SET deleted_at = now()），不调用 deleteById")
    void deleteZoneWithoutAssetsSucceeds() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(zone(ZONE_ID, 1));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(false);

        service.deleteProjectZone(PROJECT_ID, ZONE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ProjectZone>> wrapper =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(projectZoneMapper).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSet()).contains("deleted_at = now()");
        verify(projectZoneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("编辑分区：已软删的分区视为不存在（防对已删行继续编辑）")
    void updateZoneOnSoftDeletedZoneIsRejected() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateProjectZone(PROJECT_ID, ZONE_ID, inputZone("A区")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("分区不存在");
        verify(projectZoneMapper, never()).updateById(any(ProjectZone.class));
    }

    @Test
    @DisplayName("删除分区：有后续记录时拒绝，且提示「后续记录」而不是「资产」")
    void deleteZoneWithRecordsIsRejected() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(zone(ZONE_ID, 1));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteProjectZone(PROJECT_ID, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("后续记录");
        verify(projectZoneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除分区：无资产无记录时写 deleted_at 软删，不调用 deleteById")
    void deleteZoneSoftDeletes() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectActiveInProject(PROJECT_ID, ZONE_ID)).thenReturn(zone(ZONE_ID, 1));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(false);

        service.deleteProjectZone(PROJECT_ID, ZONE_ID);

        verify(projectZoneMapper, never()).deleteById(anyLong());
        verify(projectZoneMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("项目整体保存：移除「有资产」的分区时整单拒绝，不再静默置空资产归属")
    void replaceZonesRejectsRemovalOfZoneWithAssets() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectList(any())).thenReturn(List.of(zone(ZONE_ID, 1)));
        when(assetMapper.selectCount(any())).thenReturn(2L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(false);

        // 走 PUT /projects/{id} 的公开入口（replaceZones 是私有辅助，测试不绕开入口）
        assertThatThrownBy(() -> service.updateProject(PROJECT_ID, zonesRemovedRequest()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("2 项资产");
        verify(projectZoneMapper, never()).deleteBatchIds(any());
        verify(assetMapper, never()).update(any(), any());
        verify(projectZoneMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("项目整体保存：移除「有后续记录」的分区时整单拒绝")
    void replaceZonesRejectsRemovalOfZoneWithRecords() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(projectZoneMapper.selectList(any())).thenReturn(List.of(zone(ZONE_ID, 1)));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.updateProject(PROJECT_ID, zonesRemovedRequest()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("后续记录");
        verify(projectZoneMapper, never()).deleteBatchIds(any());
        verify(assetMapper, never()).update(any(), any());
        verify(projectZoneMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("删除项目：分区有后续记录时整单拒绝，且不删分区也不删项目")
    void deleteProjectRejectsWhenZoneHasRecords() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecords(RecordOwnerType.PROJECT, PROJECT_ID)).thenReturn(false);
        when(projectZoneMapper.selectList(any())).thenReturn(List.of(zone(ZONE_ID, 1)));
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteProject(PROJECT_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("后续记录")
                .hasMessageContaining("分区9");

        verify(projectZoneMapper, never()).delete(any());
        verify(projectMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除项目：项目自身有后续记录时整单拒绝")
    void deleteProjectRejectsWhenProjectHasRecords() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecords(RecordOwnerType.PROJECT, PROJECT_ID)).thenReturn(true);
        // 刻意不桩 projectZoneMapper.selectList：项目级记录先于分区守卫抛错，这行不会被查到；
        // 桩了反而会因为 STRICT_STUBS 报「多余桩」而变红。

        assertThatThrownBy(() -> service.deleteProject(PROJECT_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("项目下已有后续记录");

        verify(projectZoneMapper, never()).delete(any());
        verify(projectMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除项目：无资产无记录时硬删分区与项目")
    void deleteProjectRemovesZonesAndProjectWhenEmpty() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID));
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(recordPresenceChecker.hasRecords(RecordOwnerType.PROJECT, PROJECT_ID)).thenReturn(false);
        when(projectZoneMapper.selectList(any())).thenReturn(List.of(zone(ZONE_ID, 1)));
        when(recordPresenceChecker.hasRecordsForZone(ZONE_ID)).thenReturn(false);

        service.deleteProject(PROJECT_ID);

        verify(projectZoneMapper).delete(any());
        verify(projectMapper).deleteById(anyLong());
    }

    private static Project project(long id) {
        Project p = new Project();
        p.setId(id);
        return p;
    }

    private static ProjectZone zone(long id, int sort) {
        ProjectZone z = new ProjectZone();
        z.setId(id);
        z.setProjectId(PROJECT_ID);
        z.setName("分区" + id);
        z.setSort(sort);
        return z;
    }

    /** 项目整体保存请求：不带任何分区 = 移除现有全部分区。 */
    private static ProjectSaveRequest zonesRemovedRequest() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("测试项目");
        request.setCompanyId(1L);
        return request;
    }

    /** 请求体形态：刻意带上错误的 projectId，用于证明归属只认路径参数。 */
    private static ProjectZone inputZone(String name) {
        ProjectZone z = new ProjectZone();
        z.setProjectId(999L);
        z.setName(name);
        return z;
    }
}
