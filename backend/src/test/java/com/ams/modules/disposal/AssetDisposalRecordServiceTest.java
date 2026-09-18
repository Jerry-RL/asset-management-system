package com.ams.modules.disposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.disposal.dto.AssetDisposalRecordView;
import com.ams.modules.disposal.entity.AssetDisposalRecord;
import com.ams.modules.disposal.mapper.AssetDisposalRecordMapper;
import com.ams.modules.disposal.service.AssetDisposalRecordService;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.record.DisposalCascadePort;
import com.ams.modules.record.DisposalCascadeSnapshot;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 级联处置的核心语义（V57）。
 *
 * <p>三条不变量，每一条都能被一次不小心的实现悄悄破掉：
 * <ol>
 *   <li><b>先快照后清空</b>：公司字段一旦被清空就再也反推不出「从哪家公司处置出去」——
 *       台账里的 {@code fromPropertyCompanyId} 必须是**清空前**的值；</li>
 *   <li><b>幂等</b>：已退出（{@code lifecycle_status='exited'}）的资产必须被跳过，
 *       既不重复清字段也不重复写台账；</li>
 *   <li><b>项目 / 分区级要逐资产展开</b>：一行处置台账对应多个资产行，且资产解析必须限定
 *       在处置对象下（不能顺手处置别的项目）。</li>
 * </ol>
 *
 * <p>桩刻意不模拟 SQL：{@code LambdaQueryWrapper} 的条件读不出来，因此「按项目查出来什么」
 * 一律显式桩定，并配反向对照（{@code alreadyExitedIsSkipped}）—— 否则「恰好返回空列表」
 * 会让断言因为错误的原因通过。
 */
class AssetDisposalRecordServiceTest {

    private static final long ASSET_ID = 7L;
    private static final long PROJECT_ID = 3L;
    private static final long ZONE_ID = 4L;
    private static final long FROM_PROPERTY_COMPANY = 11L;
    private static final long FROM_OPERATING_COMPANY = 12L;

    private final AssetDisposalRecordMapper disposalRecordMapper =
            mock(AssetDisposalRecordMapper.class);
    private final AssetMapper assetMapper = mock(AssetMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ProjectZoneMapper projectZoneMapper = mock(ProjectZoneMapper.class);
    private final CompanyMapper companyMapper = mock(CompanyMapper.class);

    private final AssetDisposalRecordService service = new AssetDisposalRecordService(
            disposalRecordMapper, assetMapper, projectMapper, projectZoneMapper, companyMapper);

    // ------------------------------------------------------------------
    // 资产级
    // ------------------------------------------------------------------

    @Test
    @DisplayName("资产级：先快照原公司，再清空产权 / 置 disposed + exited，并写台账")
    void assetTargetSnapshotsThenClears() {
        Asset asset = asset(ASSET_ID, PROJECT_ID, ZONE_ID);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset);
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        int disposed = service.cascadeDispose(DisposalCascadePort.TARGET_ASSET, ASSET_ID,
                meta(), 55L, null);

        assertThat(disposed).isEqualTo(1);

        ArgumentCaptor<AssetDisposalRecord> ledger =
                ArgumentCaptor.forClass(AssetDisposalRecord.class);
        verify(disposalRecordMapper).insert(ledger.capture());
        assertThat(ledger.getValue().getAssetId()).isEqualTo(ASSET_ID);
        assertThat(ledger.getValue().getTargetType()).isEqualTo("asset");
        assertThat(ledger.getValue().getTargetId()).isEqualTo(ASSET_ID);
        assertThat(ledger.getValue().getSourceOrderId()).isEqualTo(55L);
        assertThat(ledger.getValue().getSourceRecordId()).isNull();
        // 快照必须是**清空前**的原值，否则追溯不到「从哪家公司处置出去」
        assertThat(ledger.getValue().getFromPropertyCompanyId()).isEqualTo(FROM_PROPERTY_COMPANY);
        assertThat(ledger.getValue().getFromOperatingCompanyId()).isEqualTo(FROM_OPERATING_COMPANY);
        assertThat(ledger.getValue().getDisposalType()).isEqualTo("sale");
        assertThat(ledger.getValue().getDisposalAmount()).isEqualByComparingTo("120.50");
        assertThat(ledger.getValue().getAmountUnit()).isEqualTo("yuan");
        assertThat(ledger.getValue().getDisposalDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(ledger.getValue().getDisposalUserName()).isEqualTo("张三");
        assertThat(ledger.getValue().getDisposedAt()).isNotNull();

        ArgumentCaptor<Asset> updated = ArgumentCaptor.forClass(Asset.class);
        verify(assetMapper).updateById(updated.capture());
        assertThat(updated.getValue().getPropertyCompanyId()).isNull();
        assertThat(updated.getValue().getOwnershipStatus()).isEqualTo("disposed");
        assertThat(updated.getValue().getLifecycleStatus()).isEqualTo("exited");
        // 经营公司刻意保留：资产仍留在原经营主体的数据范围里（需求只要求脱离**产权**公司）
        assertThat(updated.getValue().getOperatingCompanyId()).isEqualTo(FROM_OPERATING_COMPANY);
    }

    @Test
    @DisplayName("已退出的资产被跳过：不重复清字段、不重复写台账（幂等的唯一判据）")
    void alreadyExitedIsSkipped() {
        Asset exited = asset(ASSET_ID, PROJECT_ID, ZONE_ID);
        exited.setLifecycleStatus("exited");
        when(assetMapper.selectById(ASSET_ID)).thenReturn(exited);

        int disposed = service.cascadeDispose(DisposalCascadePort.TARGET_ASSET, ASSET_ID,
                meta(), 55L, null);

        assertThat(disposed).isZero();
        verify(disposalRecordMapper, never()).insert(any(AssetDisposalRecord.class));
        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    @Test
    @DisplayName("并发改动资产：乐观锁返回 0 时抛 409，整单回滚（绝不部分处置）")
    void optimisticLockConflictFailsLoudly() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(ASSET_ID, PROJECT_ID, ZONE_ID));
        when(assetMapper.updateById(any(Asset.class))).thenReturn(0);

        assertThatThrownBy(() -> service.cascadeDispose(
                DisposalCascadePort.TARGET_ASSET, ASSET_ID, meta(), 55L, null))
                .isInstanceOf(AppException.class);
    }

    // ------------------------------------------------------------------
    // 项目 / 分区级
    // ------------------------------------------------------------------

    @Test
    @DisplayName("项目级：逐资产展开 —— 每个资产一行台账，全部脱离原产权公司")
    void projectTargetExpandsToEveryAsset() {
        Asset first = asset(101L, PROJECT_ID, ZONE_ID);
        Asset second = asset(102L, PROJECT_ID, ZONE_ID);
        when(assetMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(first, second)));
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        int disposed = service.cascadeDispose(DisposalCascadePort.TARGET_PROJECT, PROJECT_ID,
                meta(), null, 88L);

        assertThat(disposed).isEqualTo(2);
        ArgumentCaptor<AssetDisposalRecord> ledger =
                ArgumentCaptor.forClass(AssetDisposalRecord.class);
        verify(disposalRecordMapper, times(2)).insert(ledger.capture());
        assertThat(ledger.getAllValues())
                .allSatisfy(row -> {
                    assertThat(row.getTargetType()).isEqualTo("project");
                    assertThat(row.getTargetId()).isEqualTo(PROJECT_ID);
                    assertThat(row.getSourceOrderId()).isNull();
                    assertThat(row.getSourceRecordId()).isEqualTo(88L);
                    assertThat(row.getFromPropertyCompanyId()).isEqualTo(FROM_PROPERTY_COMPANY);
                });
        assertThat(ledger.getAllValues()).extracting(AssetDisposalRecord::getAssetId)
                .containsExactly(101L, 102L);
    }

    @Test
    @DisplayName("分区级：同样是逐资产展开，目标 id 是分区")
    void zoneTargetExpandsToEveryAsset() {
        when(assetMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(asset(201L, PROJECT_ID, ZONE_ID))));
        when(assetMapper.updateById(any(Asset.class))).thenReturn(1);

        int disposed = service.cascadeDispose(DisposalCascadePort.TARGET_ZONE, ZONE_ID,
                meta(), null, 89L);

        assertThat(disposed).isEqualTo(1);
        ArgumentCaptor<AssetDisposalRecord> ledger =
                ArgumentCaptor.forClass(AssetDisposalRecord.class);
        verify(disposalRecordMapper).insert(ledger.capture());
        assertThat(ledger.getValue().getTargetType()).isEqualTo("zone");
        assertThat(ledger.getValue().getTargetId()).isEqualTo(ZONE_ID);
    }

    @Test
    @DisplayName("非法处置对象：400 且不查库、不写库 —— 不退回默认层级")
    void unknownTargetTypeIsRejected() {
        assertThatThrownBy(() -> service.cascadeDispose("warehouse", 1L, meta(), null, 1L))
                .isInstanceOf(AppException.class);

        verify(assetMapper, never()).selectList(any());
        verify(disposalRecordMapper, never()).insert(any(AssetDisposalRecord.class));
    }

    @Test
    @DisplayName("资产不存在或已软删：404，不写台账")
    void missingAssetIsNotFound() {
        Asset deleted = asset(ASSET_ID, PROJECT_ID, ZONE_ID);
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(assetMapper.selectById(ASSET_ID)).thenReturn(deleted);

        assertThatThrownBy(() -> service.cascadeDispose(
                DisposalCascadePort.TARGET_ASSET, ASSET_ID, meta(), 55L, null))
                .isInstanceOf(AppException.class);

        verify(disposalRecordMapper, never()).insert(any(AssetDisposalRecord.class));
    }

    // ------------------------------------------------------------------
    // 读：名称回填
    // ------------------------------------------------------------------

    @Test
    @DisplayName("列表：资产 / 公司名一次批量回填，不做逐行查询")
    void pageFillsDisplayNamesInBatch() {
        AssetDisposalRecord row = new AssetDisposalRecord();
        row.setId(1L);
        row.setAssetId(ASSET_ID);
        row.setTargetType("project");
        row.setTargetId(PROJECT_ID);
        row.setFromPropertyCompanyId(FROM_PROPERTY_COMPANY);
        when(disposalRecordMapper.selectPage(any(), any())).thenReturn(pageOf(row));

        Asset asset = asset(ASSET_ID, PROJECT_ID, ZONE_ID);
        asset.setAssetNo("A-001");
        asset.setName("厂房A");
        when(assetMapper.selectBatchIds(any())).thenReturn(new ArrayList<>(List.of(asset)));

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("园区一期");
        when(projectMapper.selectBatchIds(any())).thenReturn(new ArrayList<>(List.of(project)));
        ProjectZone zone = new ProjectZone();
        zone.setId(ZONE_ID);
        zone.setName("A区");
        when(projectZoneMapper.selectBatchIds(any())).thenReturn(new ArrayList<>(List.of(zone)));

        Company company = new Company();
        company.setId(FROM_PROPERTY_COMPANY);
        company.setName("城投集团");
        when(companyMapper.selectBatchIds(any())).thenReturn(new ArrayList<>(List.of(company)));

        PageResult<AssetDisposalRecordView> result = service.page(1, 10, null, null, null, null);

        assertThat(result.getTotal()).isEqualTo(1);
        AssetDisposalRecordView view = result.getList().get(0);
        assertThat(view.getAssetNo()).isEqualTo("A-001");
        assertThat(view.getAssetName()).isEqualTo("厂房A");
        assertThat(view.getProjectName()).isEqualTo("园区一期");
        assertThat(view.getZoneName()).isEqualTo("A区");
        assertThat(view.getFromPropertyCompanyName()).isEqualTo("城投集团");

        // 批量取名：四个名称源各一次查询，不随行数增长
        verify(assetMapper).selectBatchIds(any());
        verify(projectMapper).selectBatchIds(any());
        verify(projectZoneMapper).selectBatchIds(any());
        verify(companyMapper).selectBatchIds(any());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private Asset asset(long id, Long projectId, Long zoneId) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setAssetNo("A-" + id);
        asset.setName("资产" + id);
        asset.setProjectId(projectId);
        asset.setZoneId(zoneId);
        asset.setPropertyCompanyId(FROM_PROPERTY_COMPANY);
        asset.setOperatingCompanyId(FROM_OPERATING_COMPANY);
        asset.setLifecycleStatus("in_book");
        asset.setOwnershipStatus("in_group");
        return asset;
    }

    private DisposalCascadeSnapshot meta() {
        return new DisposalCascadeSnapshot("sale", new BigDecimal("120.50"),
                DisposalCascadeSnapshot.UNIT_YUAN, LocalDate.of(2026, 8, 2), 9L, "张三", "闲置处置");
    }

    private Page<AssetDisposalRecord> pageOf(AssetDisposalRecord... rows) {
        Page<AssetDisposalRecord> page = new Page<>(1, 10);
        page.setRecords(List.of(rows));
        page.setTotal(rows.length);
        return page;
    }
}
