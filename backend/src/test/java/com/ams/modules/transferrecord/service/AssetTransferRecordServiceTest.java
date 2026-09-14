package com.ams.modules.transferrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.modules.transferrecord.dto.AssetTransferRecordInput;
import com.ams.modules.transferrecord.dto.AssetTransferRecordView;
import com.ams.modules.transferrecord.dto.TransferRecordAssetOption;
import com.ams.modules.transferrecord.entity.AssetTransferRecord;
import com.ams.modules.transferrecord.entity.AssetTransferRecordAsset;
import com.ams.modules.transferrecord.mapper.AssetTransferRecordAssetMapper;
import com.ams.modules.transferrecord.mapper.AssetTransferRecordMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 资产调拨记录的校验闭环与生效落库（V55）。
 *
 * <p>关注点分三段：**草稿校验**（公司与部门的一致性、责任人与部门的一致性、资产归属、
 * 已退出资产、去重）、**生效**（写哪两个字段、快照取哪一刻的值、幂等、乐观锁）、
 * **本模块刻意不做的事**（不校验抵押、不拦「新部门等于原部门」、不接审批）。
 */
class AssetTransferRecordServiceTest {

    private static final long COMPANY = 2L;
    private static final long OLD_DEPT = 11L;
    private static final long NEW_DEPT = 12L;
    private static final long OLD_USER = 21L;
    private static final long NEW_USER = 22L;
    private static final long ASSET_A = 101L;
    private static final long ASSET_B = 102L;

    /** mock 的 insert 不会回填自增主键，故用固定值模拟数据库回填。 */
    private static final long NEW_ID = 7L;

    private AssetTransferRecordMapper recordMapper;
    private AssetTransferRecordAssetMapper recordAssetMapper;
    private AssetMapper assetMapper;
    private ProjectMapper projectMapper;
    private ProjectZoneMapper projectZoneMapper;
    private CompanyMapper companyMapper;
    private DepartmentMapper departmentMapper;
    private UserMapper userMapper;
    private CompanyTreeService companyTreeService;
    private RecordSheetService recordSheetService;

    /** 假装的明细表：insert 时收进来，selectList 时按主单 id 吐回去。 */
    private final List<AssetTransferRecordAsset> detailRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        recordMapper = mock(AssetTransferRecordMapper.class);
        recordAssetMapper = mock(AssetTransferRecordAssetMapper.class);
        assetMapper = mock(AssetMapper.class);
        projectMapper = mock(ProjectMapper.class);
        projectZoneMapper = mock(ProjectZoneMapper.class);
        companyMapper = mock(CompanyMapper.class);
        departmentMapper = mock(DepartmentMapper.class);
        userMapper = mock(UserMapper.class);
        companyTreeService = mock(CompanyTreeService.class);
        recordSheetService = mock(RecordSheetService.class);
        detailRows.clear();

        // 公司有效 / 部门 11、12 属于 2 号公司 / 责任人在各自部门下 —— 默认全部成立，
        // 单条用例只推翻它关心的那一项，避免每个用例都抄一遍夹具
        when(companyTreeService.isActiveCompany(COMPANY)).thenReturn(true);
        when(departmentMapper.selectById(OLD_DEPT)).thenReturn(department(OLD_DEPT, COMPANY));
        when(departmentMapper.selectById(NEW_DEPT)).thenReturn(department(NEW_DEPT, COMPANY));
        when(userMapper.selectById(OLD_USER)).thenReturn(user(OLD_USER, OLD_DEPT));
        when(userMapper.selectById(NEW_USER)).thenReturn(user(NEW_USER, NEW_DEPT));
        when(companyMapper.selectBatchIds(any())).thenReturn(List.of(company(COMPANY)));

        // mock 的 selectBatchIds 默认返回 null（真实实现返回空集合），不桩住会让回填名称 NPE
        when(assetMapper.selectBatchIds(any())).thenReturn(List.of());
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of());
        when(projectZoneMapper.selectBatchIds(any())).thenReturn(List.of());
        when(departmentMapper.selectBatchIds(any())).thenReturn(List.of());
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());
        when(recordSheetService.toAttachmentRefs(any(), any())).thenReturn(List.of());
        when(recordMapper.selectPage(any(), any())).thenReturn(new Page<>());

        // 明细：insert 收进假表并回填 id；selectList 按主单 id 吐回（草稿编辑、生效快照、
        // 详情展开都依赖它）
        when(recordAssetMapper.insert(any(AssetTransferRecordAsset.class))).thenAnswer(inv -> {
            AssetTransferRecordAsset row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId((long) (detailRows.size() + 1));
            }
            detailRows.removeIf(existing -> existing.getId().equals(row.getId()));
            detailRows.add(row);
            return 1;
        });
        when(recordAssetMapper.selectList(any())).thenAnswer(inv -> List.copyOf(detailRows));

        // insert 之后 create/update 会立刻 get(id) 回读：让 selectById 能返回同一条，
        // 否则 mock 下主键为 null、回读必然 404 —— 那是测试夹具的缺口，不是被测行为
        when(recordMapper.insert(any(AssetTransferRecord.class))).thenAnswer(inv -> {
            AssetTransferRecord row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(NEW_ID);
            }
            when(recordMapper.selectById(row.getId())).thenReturn(row);
            return 1;
        });
    }

    private AssetTransferRecordService newService() {
        return new AssetTransferRecordService(
                recordMapper,
                recordAssetMapper,
                assetMapper,
                projectMapper,
                projectZoneMapper,
                companyMapper,
                departmentMapper,
                userMapper,
                companyTreeService,
                recordSheetService);
    }

    // ------------------------------------------------------------------
    // 草稿：校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新责任部门不属于所选公司 -> 400（否则会把资产交接给别的公司的部门）")
    void rejectsDepartmentOfAnotherCompany() {
        when(departmentMapper.selectById(NEW_DEPT)).thenReturn(department(NEW_DEPT, 999L));

        assertThatThrownBy(() -> newService().create(validInput()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选公司");
    }

    @Test
    @DisplayName("新责任人不属于新责任部门 -> 400（否则资产上会出现「部门与人对不上」）")
    void rejectsUserNotInTargetDepartment() {
        when(userMapper.selectById(NEW_USER)).thenReturn(user(NEW_USER, OLD_DEPT));

        assertThatThrownBy(() -> newService().create(validInput()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于新责任部门");
    }

    @Test
    @DisplayName("所属公司已停用 -> 400，且不插入任何行")
    void rejectsInactiveCompany() {
        when(companyTreeService.isActiveCompany(COMPANY)).thenReturn(false);

        assertThatThrownBy(() -> newService().create(validInput()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已停用");
        verify(recordMapper, never()).insert(any(AssetTransferRecord.class));
    }

    @Test
    @DisplayName("资产不属于所选公司 -> 400（资产下拉按公司过滤，但请求体可以绕过前端）")
    void rejectsAssetOfAnotherCompany() {
        stubAssets();
        when(assetMapper.selectById(ASSET_A)).thenReturn(asset(ASSET_A, 777L));

        assertThatThrownBy(() -> newService().create(validInput()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不属于所选公司");
    }

    @Test
    @DisplayName("已退出的资产不可调拨 -> 400")
    void rejectsExitedAsset() {
        stubAssets();
        Asset exited = asset(ASSET_A, COMPANY);
        exited.setLifecycleStatus("exited");
        when(assetMapper.selectById(ASSET_A)).thenReturn(exited);

        assertThatThrownBy(() -> newService().create(validInput()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已退出");
    }

    @Test
    @DisplayName("资产重复勾选会被去重：明细只写一行，不会在生效时被写两遍")
    void dedupesAssetIds() {
        stubAssets();
        AssetTransferRecordInput input = validInput();
        input.setAssetIds(List.of(ASSET_A, ASSET_A, ASSET_B));

        newService().create(input);

        ArgumentCaptor<AssetTransferRecordAsset> rows =
                ArgumentCaptor.forClass(AssetTransferRecordAsset.class);
        verify(recordAssetMapper, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(AssetTransferRecordAsset::getAssetId)
                .containsExactly(ASSET_A, ASSET_B);
    }

    @Test
    @DisplayName("前责任部门可以不填：多资产可能来自不同部门，强制填会挡住正常业务")
    void allowsMissingFromDepartment() {
        stubAssets();
        AssetTransferRecordInput input = validInput();
        input.setFromDepartmentId(null);

        AssetTransferRecordView view = newService().create(input);

        assertThat(view).isNotNull();
        assertThat(detailRows).allSatisfy(row -> assertThat(row.getFromDepartmentId()).isNull());
    }

    @Test
    @DisplayName("分页列表：回填公司名与资产数（列表页每行的「公司 / 资产数」不靠前端再发请求）")
    void pagesWithFilters() {
        stubAssets();
        newService().create(validInput());
        stubSelectPageWithInsertedRecord();

        PageResult<AssetTransferRecordView> result =
                newService().page(1, 10, "draft", COMPANY, null);

        assertThat(result.getList()).hasSize(1);
        AssetTransferRecordView view = result.getList().get(0);
        assertThat(view.getId()).isEqualTo(NEW_ID);
        assertThat(view.getCompanyName()).isEqualTo("公司" + COMPANY);
        assertThat(view.getAssetCount()).isEqualTo(2);
        // 列表页不展开明细：展开会让每一行多一次 join
        assertThat(view.getAssets()).isNull();
    }

    @Test
    @DisplayName("详情：展开明细，并给出「原责任部门 / 原责任人」的名字（快照值，不是资产现值）")
    void detailExpandsAssetsWithSnapshotNames() {
        stubAssetsWithResponsible();
        newService().create(validInput());
        when(departmentMapper.selectBatchIds(any())).thenReturn(List.of(department(OLD_DEPT, COMPANY)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(OLD_USER, OLD_DEPT)));

        AssetTransferRecordView view = newService().get(NEW_ID);

        assertThat(view.getAssets()).hasSize(2);
        assertThat(view.getAssets().get(0).getFromDepartmentName()).isEqualTo("部门" + OLD_DEPT);
        assertThat(view.getAssets().get(0).getFromUserName()).isEqualTo("员工" + OLD_USER);
    }

    @Test
    @DisplayName("资产下拉：按所属公司过滤，并把项目 / 分区名一次补齐（前端 4 段展示要用）")
    void assetOptionsFilterByCompany() {
        Asset asset = asset(ASSET_A, COMPANY);
        asset.setProjectId(5L);
        asset.setZoneId(6L);
        Page<Asset> page = new Page<>(1, 50);
        page.setRecords(List.of(asset));
        when(assetMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<TransferRecordAssetOption> options =
                newService().assetOptions(COMPANY, null, 1, 50);

        assertThat(options.getList()).hasSize(1);
        assertThat(options.getList().get(0).getAssetId()).isEqualTo(ASSET_A);
    }

    @Test
    @DisplayName("资产下拉：未选公司时 400（没公司就无从过滤，返回全量等于让用户选完再被拒）")
    void assetOptionsNeedsCompany() {
        assertThatThrownBy(() -> newService().assetOptions(null, null, 1, 50))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请先选择所属公司");
    }

    // ------------------------------------------------------------------
    // 生效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生效：写责任部门与责任人两个字段，并把「生效那一刻」的原值写进明细快照")
    void effectWritesDepartmentAndUser() {
        stubAssets();
        newService().create(validInput());

        // 草稿躺了一阵子：资产此时的责任岗位是 OLD_*（与起草时不同）
        Asset asset = asset(ASSET_A, COMPANY);
        asset.setResponsibleDepartmentId(OLD_DEPT);
        asset.setResponsibleUserId(OLD_USER);
        when(assetMapper.selectById(ASSET_A)).thenReturn(asset);
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        newService().effect(NEW_ID);

        assertThat(asset.getResponsibleDepartmentId()).isEqualTo(NEW_DEPT);
        assertThat(asset.getResponsibleUserId()).isEqualTo(NEW_USER);
        assertThat(detailRows)
                .filteredOn(row -> ASSET_A == row.getAssetId())
                .singleElement()
                .satisfies(row -> {
                    // 快照取的是「改之前」的值，不是改完的新值
                    assertThat(row.getFromDepartmentId()).isEqualTo(OLD_DEPT);
                    assertThat(row.getFromUserId()).isEqualTo(OLD_USER);
                });
        ArgumentCaptor<AssetTransferRecord> saved =
                ArgumentCaptor.forClass(AssetTransferRecord.class);
        verify(recordMapper, atLeastOnce()).updateById(saved.capture());
        assertThat(saved.getAllValues())
                .anySatisfy(row -> {
                    assertThat(row.getStatus()).isEqualTo("completed");
                    assertThat(row.getEffectedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("生效是幂等的：已完成的单直接返回，不把责任部门再写一遍")
    void effectIsIdempotent() {
        when(recordMapper.selectById(NEW_ID)).thenReturn(completedRecord());

        newService().effect(NEW_ID);

        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    @Test
    @DisplayName("资产被并发修改 -> 409 并整单回滚（改一半是最难排查的状态）")
    void effectConflictsOnOptimisticLock() {
        stubAssets();
        newService().create(validInput());
        when(assetMapper.updateById(any(Asset.class))).thenReturn(0);

        assertThatThrownBy(() -> newService().effect(NEW_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("并发修改");
    }

    @Test
    @DisplayName("非草稿不可修改或删除")
    void completedRecordCannotBeEdited() {
        when(recordMapper.selectById(NEW_ID)).thenReturn(completedRecord());

        assertThatThrownBy(() -> newService().update(NEW_ID, validInput()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("只有草稿");
        assertThatThrownBy(() -> newService().delete(NEW_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("只有草稿");
    }

    @Test
    @DisplayName("删除是软删：单据带 deleted_at（附件行不清理，物理删会丢掉审计信息）")
    void deleteIsSoft() {
        stubAssets();
        newService().create(validInput());

        newService().delete(NEW_ID);

        ArgumentCaptor<AssetTransferRecord> saved =
                ArgumentCaptor.forClass(AssetTransferRecord.class);
        verify(recordMapper, atLeastOnce()).updateById(saved.capture());
        assertThat(saved.getAllValues())
                .anySatisfy(row -> assertThat(row.getDeletedAt()).isNotNull());
        // 附件行没有被删：delete 只走主单
        verify(recordAssetMapper, never()).deleteById(any(Long.class));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void stubSelectPageWithInsertedRecord() {
        ArgumentCaptor<AssetTransferRecord> inserted =
                ArgumentCaptor.forClass(AssetTransferRecord.class);
        verify(recordMapper).insert(inserted.capture());
        Page<AssetTransferRecord> page = new Page<>(1, 10);
        page.setRecords(List.of(inserted.getValue()));
        when(recordMapper.selectPage(any(), any())).thenReturn(page);
    }

    private AssetTransferRecord completedRecord() {
        AssetTransferRecord done = new AssetTransferRecord();
        done.setId(NEW_ID);
        done.setCompanyId(COMPANY);
        done.setToDepartmentId(NEW_DEPT);
        done.setToUserId(NEW_USER);
        done.setStatus("completed");
        return done;
    }

    private AssetTransferRecordInput validInput() {
        AssetTransferRecordInput input = new AssetTransferRecordInput();
        input.setCompanyId(COMPANY);
        input.setFromDepartmentId(OLD_DEPT);
        input.setToDepartmentId(NEW_DEPT);
        input.setToUserId(NEW_USER);
        input.setApprovalDeadline(LocalDateTime.of(2026, 10, 1, 18, 0));
        input.setReason("岗位调整");
        input.setRemark("交接完成");
        input.setAssetIds(List.of(ASSET_A, ASSET_B));
        return input;
    }

    private void stubAssets() {
        when(assetMapper.selectById(ASSET_A)).thenReturn(asset(ASSET_A, COMPANY));
        when(assetMapper.selectById(ASSET_B)).thenReturn(asset(ASSET_B, COMPANY));
    }

    /** 起草时资产已有责任岗位：用来验证「原值」被写进明细快照。 */
    private void stubAssetsWithResponsible() {
        when(assetMapper.selectById(ASSET_A)).thenReturn(assetWithResponsible(ASSET_A));
        when(assetMapper.selectById(ASSET_B)).thenReturn(assetWithResponsible(ASSET_B));
    }

    private Asset assetWithResponsible(Long id) {
        Asset asset = asset(id, COMPANY);
        asset.setResponsibleDepartmentId(OLD_DEPT);
        asset.setResponsibleUserId(OLD_USER);
        return asset;
    }

    private Asset asset(Long id, Long companyId) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setAssetCompanyId(companyId);
        asset.setName("资产" + id);
        asset.setAssetNo("ZC-" + id);
        asset.setLifecycleStatus("in_use");
        return asset;
    }

    private Department department(Long id, Long companyId) {
        Department department = new Department();
        department.setId(id);
        department.setCompanyId(companyId);
        department.setName("部门" + id);
        department.setStatus(1);
        return department;
    }

    private User user(Long id, Long departmentId) {
        User user = new User();
        user.setId(id);
        user.setDepartmentId(departmentId);
        user.setCompanyId(COMPANY);
        user.setName("员工" + id);
        user.setStatus(1);
        return user;
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setId(id);
        company.setName("公司" + id);
        company.setStatus(1);
        return company;
    }
}
