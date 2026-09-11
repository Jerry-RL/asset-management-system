package com.ams.modules.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.OccupancyType;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.AssetOccupancyService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.asset.service.LeaseStatusDeriver;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.billing.service.PrepayService;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.migration.entity.MigrationBatch;
import com.ams.modules.migration.mapper.MigrationBatchMapper;
import com.ams.modules.migration.mapper.MigrationImportLogMapper;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.modules.contract.mapper.DepositTransactionMapper;
import com.ams.modules.dunning.service.DunningService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 期初租控建账测试（评审 P0-10、设计 §12.5）。
 *
 * <p>验证的核心命题：<b>迁移不得直写 {@code asset.lease_control_status}</b> ——
 * 该列是占用集合的物化派生列，只能由 {@code LeaseStatusDeriver} 写入。
 * 原实现直接置 {@code LEASED}，导致两类错误：
 * <ol>
 *   <li>绕过占用唯一入口，与派生器形成两处真相；</li>
 *   <li>把多合同（部分出租）资产一律写成 {@code LEASED}，即 M2 缺陷。</li>
 * </ol>
 * 新口径把历史合同登记为<b>带租期的历史占用</b>，由派生推出正确状态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MigrationServiceLeaseControlTest {

    private static final long BATCH_ID = 7L;

    @Mock
    private MigrationBatchMapper batchMapper;
    @Mock
    private MigrationImportLogMapper importLogMapper;
    @Mock
    private ContractMapper contractMapper;
    @Mock
    private BillMapper billMapper;
    @Mock
    private PaymentMapper paymentMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private TenantMapper tenantMapper;
    @Mock
    private DepositTransactionMapper depositTransactionMapper;
    @Mock
    private PrepayService prepayService;
    @Mock
    private DunningService dunningService;
    @Mock
    private AssetOccupancyService occupancyService;
    @Mock
    private AssetUnitService assetUnitService;
    @Mock
    private LeaseStatusDeriver leaseStatusDeriver;

    private MigrationService service;

    @BeforeEach
    void setUp() {
        service = new MigrationService(batchMapper, importLogMapper, contractMapper, billMapper,
                paymentMapper, assetMapper, tenantMapper, depositTransactionMapper, prepayService,
                dunningService, new ObjectMapper(), occupancyService, assetUnitService,
                leaseStatusDeriver);
        when(batchMapper.selectById(BATCH_ID)).thenReturn(new MigrationBatch());
    }

    @Test
    @DisplayName("非终态合同登记为带租期的历史占用，且不直写租控状态列")
    void registersContractAsDatedOccupancyWithoutWritingStatusColumn() {
        Contract contract = contract(1L, 100L, 11L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(contractMapper.selectList(any())).thenReturn(List.of(contract));
        when(occupancyService.hasAnyBySubject(any(), eq(1L))).thenReturn(false);
        when(assetUnitService.resolveForLease(100L, 11L)).thenReturn(unit(11L, 100L));
        when(assetMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.initLeaseControl(BATCH_ID);

        // 区间取合同租期（历史区间），来源为 contract
        verify(occupancyService).occupy(eq(11L), eq(OccupancyType.CONTRACT),
                eq(AssetOccupancyService.SUBJECT_CONTRACT), eq(1L),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31)), eq(null),
                any());
        assertThat(result).containsEntry("occupiedRegistered", 1);

        // 关键回归：资产状态列由派生器负责，迁移不得直接改
        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    @Test
    @DisplayName("已登记过占用的合同幂等跳过（历史区间有 date_to，不能用未收口判定）")
    void existingOccupancyIsSkippedIdempotently() {
        Contract contract = contract(2L, 100L, 12L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        when(contractMapper.selectList(any())).thenReturn(List.of(contract));
        when(occupancyService.hasAnyBySubject(any(), eq(2L))).thenReturn(true);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.initLeaseControl(BATCH_ID);

        assertThat(result).containsEntry("skippedExisting", 1)
                .containsEntry("occupiedRegistered", 0);
        verify(occupancyService, never()).occupy(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("租期缺失或非法：计入 failed 而不抛错，整批继续")
    void illegalLeasePeriodIsCountedNotThrown() {
        Contract bad = contract(3L, 100L, null, null, null);
        when(contractMapper.selectList(any())).thenReturn(List.of(bad));
        when(assetMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.initLeaseControl(BATCH_ID);

        assertThat(result).containsEntry("failed", 1);
        verify(occupancyService, never()).occupy(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("单条占用登记失败不中断整批：失败留痕，后续合同继续处理")
    void singleFailureDoesNotAbortBatch() {
        Contract first = contract(4L, 100L, 14L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        Contract second = contract(5L, 100L, 15L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30));
        when(contractMapper.selectList(any())).thenReturn(List.of(first, second));
        when(assetUnitService.resolveForLease(100L, 14L)).thenReturn(unit(14L, 100L));
        when(assetUnitService.resolveForLease(100L, 15L)).thenReturn(unit(15L, 100L));
        doThrow(new AppException(ErrorCode.CONFLICT, "该单元已被占用"))
                .when(occupancyService).occupy(eq(14L), any(), any(), any(), any(), any(), any(), any());
        when(assetMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.initLeaseControl(BATCH_ID);

        assertThat(result).containsEntry("failed", 1).containsEntry("occupiedRegistered", 1);
        // 第二条仍被处理
        verify(occupancyService).occupy(eq(15L), any(), any(), any(), any(), any(), any(), any());
        // 失败留痕
        verify(importLogMapper, times(2)).insert(any(com.ams.modules.migration.entity.MigrationImportLog.class));
    }

    @Test
    @DisplayName("合同未挂单元：先补齐单元再重试解析，不直接失败")
    void missingUnitIsBackfilledThenResolved() {
        Contract contract = contract(6L, 100L, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(contractMapper.selectList(any())).thenReturn(List.of(contract));
        when(assetUnitService.resolveForLease(100L, null))
                .thenThrow(new AppException(ErrorCode.CONFLICT, "资产无可租单元"))
                .thenReturn(unit(21L, 100L));
        when(assetUnitService.ensureUnitForAsset(100L)).thenReturn(unit(21L, 100L));
        when(assetMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.initLeaseControl(BATCH_ID);

        verify(assetUnitService).ensureUnitForAsset(100L);
        verify(occupancyService).occupy(eq(21L), any(), any(), any(), any(), any(), any(), any());
        assertThat(result).containsEntry("occupiedRegistered", 1);
    }

    @Test
    @DisplayName("空置归位：补齐单元 + 派生刷新 + 仅补记空置原因，不写状态列本身")
    void vacantAssetIsDerivedNotForceWritten() {
        Asset asset = new Asset();
        asset.setId(100L);
        asset.setLeaseControlStatus(null);
        when(contractMapper.selectList(any())).thenReturn(List.of());
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));

        // 派生器刷新后资产被推出为空置
        Asset refreshed = new Asset();
        refreshed.setId(100L);
        refreshed.setLeaseControlStatus(LeaseControlStatus.VACANT);
        refreshed.setVacantReason(null);
        when(assetMapper.selectById(100L)).thenReturn(refreshed);
        when(assetUnitService.ensureUnitForAsset(100L)).thenReturn(unit(31L, 100L));

        Map<String, Object> result = service.initLeaseControl(BATCH_ID);

        verify(assetUnitService).ensureUnitForAsset(100L);
        verify(leaseStatusDeriver).refresh(100L);
        // 只写「空置原因」这类业务属性，状态列已由派生器写入
        assertThat(refreshed.getVacantReason()).isEqualTo("migration_init");
        assertThat(result).containsEntry("vacantInited", 1);
    }

    @Test
    @DisplayName("空置归位查询排除软删资产（Asset 未映射 deleted_at，用列名过滤）")
    void vacantScanFiltersSoftDeletedRows() {
        when(contractMapper.selectList(any())).thenReturn(List.of());
        when(assetMapper.selectList(any())).thenReturn(List.of());

        service.initLeaseControl(BATCH_ID);

        // 断言传给 Mapper 的包装器带上了 deleted_at IS NULL 条件
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<QueryWrapper<Asset>> captor =
                org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(assetMapper).selectList(captor.capture());
        assertThat(captor.getValue().getTargetSql())
                .as("软删资产不得参与空置归位，否则会被补建单元")
                .containsIgnoringCase("deleted_at");
    }

    @Test
    @DisplayName("已有空置原因的资产不被覆盖（不冲刷业务数据）")
    void existingVacantReasonIsPreserved() {
        Asset asset = new Asset();
        asset.setId(100L);
        when(contractMapper.selectList(any())).thenReturn(List.of());
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));

        Asset refreshed = new Asset();
        refreshed.setId(100L);
        refreshed.setLeaseControlStatus(LeaseControlStatus.VACANT);
        refreshed.setVacantReason("业主装修");
        when(assetMapper.selectById(100L)).thenReturn(refreshed);
        when(assetUnitService.ensureUnitForAsset(100L)).thenReturn(unit(31L, 100L));

        Map<String, Object> result = service.initLeaseControl(BATCH_ID);

        assertThat(refreshed.getVacantReason()).isEqualTo("业主装修");
        assertThat(result).containsEntry("vacantInited", 0);
        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    @Test
    @DisplayName("没有任何资产时返回零值而不报错")
    void emptyDatabaseIsHandled() {
        when(contractMapper.selectList(any())).thenReturn(List.of());
        when(assetMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.initLeaseControl(BATCH_ID);

        assertThat(result).containsEntry("occupiedRegistered", 0)
                .containsEntry("skippedExisting", 0)
                .containsEntry("failed", 0)
                .containsEntry("vacantInited", 0);
        verify(assetUnitService, never()).ensureUnitForAsset(anyLong());
    }

    private Contract contract(Long id, Long assetId, Long unitId, LocalDate from, LocalDate to) {
        Contract c = new Contract();
        c.setId(id);
        c.setAssetId(assetId);
        c.setAssetUnitId(unitId);
        c.setStartDate(from);
        c.setEndDate(to);
        return c;
    }

    private AssetUnit unit(Long id, Long assetId) {
        AssetUnit unit = new AssetUnit();
        unit.setId(id);
        unit.setAssetId(assetId);
        unit.setUnitNo("U-" + id);
        unit.setArea(new java.math.BigDecimal("100.00"));
        return unit;
    }
}
