package com.ams.modules.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.AssetStructureLog;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetStructureLogMapper;
import com.ams.modules.asset.mapper.AssetUnitMapper;
import com.ams.modules.asset.service.AssetOccupancyService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.asset.service.LeaseStatusDeriver;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 计租单元拆分 / 合并测试（ADR-0021 决策 B、C）。
 *
 * <p>锁死四类规则：
 * <ol>
 *   <li><b>守恒</b>：面积必须精确守恒，占位单元（面积 0）一律拒绝拆分（缺陷 D-15）；</li>
 *   <li><b>分摊而非复制</b>：底价与可租面积按面积比例拆到子单元（缺陷 D-06）；</li>
 *   <li><b>前置以占用表为准</b>：有未收口占用 / 生效招租 / 在押即拒绝（缺陷 D-04）；</li>
 *   <li><b>合并是拆分的逆操作</b>：保留排序最靠前的单元，其余软删，面积与底价相加（规则 S12）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetUnitSplitMergeTest {

    private static final long ASSET_ID = 100L;
    private static final long SRC_UNIT_ID = 7L;

    @Mock
    private AssetUnitMapper unitMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private AssetOccupancyService occupancyService;
    @Mock
    private LeaseListingMapper listingMapper;
    @Mock
    private LeaseStatusDeriver deriver;
    @Mock
    private CertificateService certificateService;
    @Mock
    private AssetStructureLogMapper structureLogMapper;

    private AssetUnitService service;

    @BeforeEach
    void setUp() {
        service = new AssetUnitService(unitMapper, assetMapper, occupancyService, listingMapper,
                deriver, certificateService, structureLogMapper, new ObjectMapper());
        // 默认：无生效招租、无未收口占用、不在押，各用例只覆写自己关心的那一条
        when(listingMapper.selectCount(any())).thenReturn(0L);
        when(occupancyService.hasOpenOccupancy(anyLong())).thenReturn(false);
    }

    // ---------------------------------------------------------------- 拆分

    @Test
    @DisplayName("拆分：底价与可租面积按面积比例分摊，不整份复制")
    void splitProratesBaseRentAndRentableArea() {
        AssetUnit src = unit(SRC_UNIT_ID, "A-1-U1", "100.00", 1);
        src.setBaseRent(new BigDecimal("1000.00"));
        // 原单元「可租 < 面积」（80/100）：拆分必须保留这个差异，而不是把可租面积放大成子面积
        src.setRentableArea(new BigDecimal("80.00"));
        when(unitMapper.selectById(SRC_UNIT_ID)).thenReturn(src);
        assignIdsOnInsert();

        List<AssetUnit> children = service.split(SRC_UNIT_ID,
                List.of(new BigDecimal("30"), new BigDecimal("70")), "分间改造");

        assertThat(children).hasSize(2);
        assertThat(children.get(0).getArea()).isEqualByComparingTo("30.00");
        assertThat(children.get(1).getArea()).isEqualByComparingTo("70.00");
        assertThat(children.get(0).getBaseRent()).isEqualByComparingTo("300.00");
        assertThat(children.get(1).getBaseRent()).isEqualByComparingTo("700.00");
        assertThat(children.get(0).getRentableArea()).isEqualByComparingTo("24.00");
        assertThat(children.get(1).getRentableArea()).isEqualByComparingTo("56.00");
        // 子单元编号沿用确定性规则（父编号 + 层级），不是随机后缀
        assertThat(children.get(0).getUnitNo()).isEqualTo("A-1-U1-1");
        assertThat(children.get(1).getUnitNo()).isEqualTo("A-1-U1-2");
        // 原单元软删保留历史身份
        assertThat(src.getDeletedAt()).isNotNull();
        verify(structureLogMapper).insert(any(AssetStructureLog.class));
        verify(deriver).refresh(ASSET_ID);
    }

    @Test
    @DisplayName("拆分：面积为 0 的占位单元一律拒绝，且不落任何子单元")
    void splitRejectsPlaceholderUnit() {
        when(unitMapper.selectById(SRC_UNIT_ID)).thenReturn(unit(SRC_UNIT_ID, "A-1-U1", "0.00", 1));

        assertThatThrownBy(() -> service.split(SRC_UNIT_ID,
                List.of(new BigDecimal("1"), new BigDecimal("1")), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("面积为 0");
        verify(unitMapper, never()).insert(any(AssetUnit.class));
    }

    @Test
    @DisplayName("拆分：面积相差 0.01 也拒绝 —— 放过去会让单元面积合计与原面积不符")
    void splitRejectsAreaMismatch() {
        when(unitMapper.selectById(SRC_UNIT_ID)).thenReturn(unit(SRC_UNIT_ID, "A-1-U1", "100.00", 1));

        List<BigDecimal> thirds = List.of(
                new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.33"));

        assertThatThrownBy(() -> service.split(SRC_UNIT_ID, thirds, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("须等于原单元面积");
        verify(unitMapper, never()).insert(any(AssetUnit.class));
    }

    @Test
    @DisplayName("拆分：少于 2 份不构成拆分")
    void splitRejectsSingleChild() {
        when(unitMapper.selectById(SRC_UNIT_ID)).thenReturn(unit(SRC_UNIT_ID, "A-1-U1", "100.00", 1));

        assertThatThrownBy(() -> service.split(SRC_UNIT_ID, List.of(new BigDecimal("100")), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("至少拆分为 2 个单元");
    }

    @Test
    @DisplayName("拆分：存在未收口占用（含预留）即拒绝，且不落任何子单元")
    void splitRejectsUnitWithOpenOccupancy() {
        when(unitMapper.selectById(SRC_UNIT_ID)).thenReturn(unit(SRC_UNIT_ID, "A-1-U1", "100.00", 1));
        when(occupancyService.hasOpenOccupancy(SRC_UNIT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.split(SRC_UNIT_ID,
                List.of(new BigDecimal("50"), new BigDecimal("50")), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("生效占用");
        verify(unitMapper, never()).insert(any(AssetUnit.class));
    }

    @Test
    @DisplayName("拆分：持有生效招租发布即拒绝（否则招租会失去标的单元）")
    void splitRejectsUnitWithActiveListing() {
        when(unitMapper.selectById(SRC_UNIT_ID)).thenReturn(unit(SRC_UNIT_ID, "A-1-U1", "100.00", 1));
        when(listingMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.split(SRC_UNIT_ID,
                List.of(new BigDecimal("50"), new BigDecimal("50")), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("生效招租发布");
        verify(unitMapper, never()).insert(any(AssetUnit.class));
    }

    @Test
    @DisplayName("拆分：在押资产不得拆分单元（权属负担与拆分互斥）")
    void splitRejectsMortgagedAsset() {
        when(unitMapper.selectById(SRC_UNIT_ID)).thenReturn(unit(SRC_UNIT_ID, "A-1-U1", "100.00", 1));
        doThrow(new AppException(ErrorCode.BUSINESS_ERROR, "资产处于在押状态，须先解押"))
                .when(certificateService).assertNotMortgaged(ASSET_ID);

        assertThatThrownBy(() -> service.split(SRC_UNIT_ID,
                List.of(new BigDecimal("50"), new BigDecimal("50")), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("在押");
        verify(unitMapper, never()).insert(any(AssetUnit.class));
    }

    // ---------------------------------------------------------------- 合并

    @Test
    @DisplayName("合并：保留排序最靠前的单元，其余软删，面积与底价相加")
    void mergeKeepsEarliestSortedUnitAndSumsAmounts() {
        AssetUnit first = unit(1L, "A-1-U1", "30.00", 10);
        first.setBaseRent(new BigDecimal("300.00"));
        AssetUnit second = unit(2L, "A-1-U2", "70.00", 20);
        second.setBaseRent(new BigDecimal("700.00"));
        when(unitMapper.selectById(1L)).thenReturn(first);
        when(unitMapper.selectById(2L)).thenReturn(second);

        // 入参刻意乱序：结果单元由 sort 决定，与传入顺序无关
        AssetUnit merged = service.merge(List.of(2L, 1L), "撤销误拆");

        assertThat(merged.getId()).isEqualTo(1L);
        assertThat(merged.getArea()).isEqualByComparingTo("100.00");
        assertThat(merged.getBaseRent()).isEqualByComparingTo("1000.00");
        assertThat(first.getDeletedAt()).isNull();
        assertThat(second.getDeletedAt()).isNotNull();
        assertThat(second.getRemark()).contains("已合并入单元 A-1-U1");
        verify(structureLogMapper).insert(any(AssetStructureLog.class));
        verify(deriver).refresh(ASSET_ID);
    }

    @Test
    @DisplayName("合并：跨资产即拒绝（单元只能在同一个资产内合并）")
    void mergeRejectsCrossAssetUnits() {
        when(unitMapper.selectById(1L)).thenReturn(unit(1L, "A-1-U1", "30.00", 1));
        AssetUnit other = unit(2L, "B-1-U1", "70.00", 1);
        other.setAssetId(200L);
        when(unitMapper.selectById(2L)).thenReturn(other);

        assertThatThrownBy(() -> service.merge(List.of(1L, 2L), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("同一资产");
        verify(deriver, never()).refresh(anyLong());
    }

    @Test
    @DisplayName("合并：任一单元有未收口占用即整体拒绝，不做部分合并")
    void mergeRejectsWhenAnyUnitOccupied() {
        when(unitMapper.selectById(1L)).thenReturn(unit(1L, "A-1-U1", "30.00", 1));
        when(unitMapper.selectById(2L)).thenReturn(unit(2L, "A-1-U2", "70.00", 2));
        when(occupancyService.hasOpenOccupancy(2L)).thenReturn(true);

        assertThatThrownBy(() -> service.merge(List.of(1L, 2L), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("生效占用");
        verify(deriver, never()).refresh(anyLong());
        verify(structureLogMapper, never()).insert(any(AssetStructureLog.class));
    }

    @Test
    @DisplayName("合并：重复 ID 去重后再判断个数，避免同一单元的面积被重复计入")
    void mergeDeduplicatesIds() {
        when(unitMapper.selectById(1L)).thenReturn(unit(1L, "A-1-U1", "30.00", 1));
        when(unitMapper.selectById(2L)).thenReturn(unit(2L, "A-1-U2", "70.00", 2));

        AssetUnit merged = service.merge(List.of(1L, 1L, 2L), null);

        assertThat(merged.getArea()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("合并：去重后只剩一个单元即拒绝，不静默降级成「什么都不做」")
    void mergeRejectsAfterDeduplication() {
        assertThatThrownBy(() -> service.merge(List.of(1L, 1L), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("2 个不同的单元");
    }

    @Test
    @DisplayName("合并：在押资产不得合并单元")
    void mergeRejectsMortgagedAsset() {
        when(unitMapper.selectById(1L)).thenReturn(unit(1L, "A-1-U1", "30.00", 1));
        when(unitMapper.selectById(2L)).thenReturn(unit(2L, "A-1-U2", "70.00", 2));
        doThrow(new AppException(ErrorCode.BUSINESS_ERROR, "资产处于在押状态，须先解押"))
                .when(certificateService).assertNotMortgaged(ASSET_ID);

        assertThatThrownBy(() -> service.merge(List.of(1L, 2L), null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("在押");
        verify(deriver, never()).refresh(anyLong());
    }

    // ---------------------------------------------------------------- 工具

    /** 模拟自增主键：拆分的结构日志需要子单元 ID，不给会让 mapping_json 里全是 null。 */
    private void assignIdsOnInsert() {
        AtomicLong seq = new AtomicLong(900L);
        doAnswer(invocation -> {
            AssetUnit unit = invocation.getArgument(0);
            unit.setId(seq.incrementAndGet());
            return 1;
        }).when(unitMapper).insert(any(AssetUnit.class));
    }

    private AssetUnit unit(long id, String unitNo, String area, int sort) {
        AssetUnit unit = new AssetUnit();
        unit.setId(id);
        unit.setAssetId(ASSET_ID);
        unit.setUnitNo(unitNo);
        unit.setUnitName(unitNo);
        unit.setArea(new BigDecimal(area));
        unit.setUnitStatus(LeaseControlStatus.VACANT);
        unit.setSort(sort);
        return unit;
    }
}
