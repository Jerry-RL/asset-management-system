package com.ams.modules.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.entity.AssetOccupancy;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.mapper.AssetOccupancyMapper;
import com.ams.modules.asset.mapper.AssetUnitMapper;
import com.ams.modules.asset.service.AssetOccupancyService;
import com.ams.modules.asset.service.LeaseStatusDeriver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 占用生效层测试（ADR-0019 决策 C、ADR-0020 决策 E）。
 *
 * <p>覆盖两条曾在实现中出现的缺陷：
 * <ol>
 *   <li><b>组合租赁只释放一条</b>（评审 P0-5）：{@code releaseBySubject} 必须收口来源单据的
 *       <b>全部</b>未收口占用，否则其余单元被 {@code EXCLUDE} 永久锁死；</li>
 *   <li><b>审批期不互斥</b>（评审 P0-2）：预留行 {@code exclusive = true}，与生效占用同等参与
 *       可用性判定，第二个同单元申请必须被拒。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AssetOccupancyServiceTest {

    private static final String SUBJECT_TYPE = AssetOccupancyService.SUBJECT_CONTRACT;
    private static final long SUBJECT_ID = 77L;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    @Mock
    private AssetOccupancyMapper occupancyMapper;
    @Mock
    private AssetUnitMapper unitMapper;
    @Mock
    private LeaseStatusDeriver deriver;

    @InjectMocks
    private AssetOccupancyService service;

    @Test
    @DisplayName("releaseBySubject 收口来源单据的全部未收口占用（组合租赁 N 个单元）")
    void releaseBySubjectClosesEveryOpenOccupancy() {
        AssetOccupancy first = occupancy(1L, 11L, OccupancyBizStatus.ACTIVE);
        AssetOccupancy second = occupancy(2L, 12L, OccupancyBizStatus.ACTIVE);
        when(occupancyMapper.selectOpenBySubject(SUBJECT_TYPE, SUBJECT_ID))
                .thenReturn(List.of(first, second));

        int closed = service.releaseBySubject(SUBJECT_TYPE, SUBJECT_ID, LocalDate.of(2026, 11, 30), "退租完成");

        assertThat(closed).isEqualTo(2);
        assertThat(first.getDateTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(second.getDateTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(first.getReleasedAt()).isNotNull();
        assertThat(second.getReleasedAt()).isNotNull();
        verify(occupancyMapper, times(2)).updateById(any(AssetOccupancy.class));
    }

    @Test
    @DisplayName("releaseUnit 只收口指定单元（部分退租）")
    void releaseUnitClosesOnlyGivenUnit() {
        AssetOccupancy target = occupancy(1L, 11L, OccupancyBizStatus.ACTIVE);
        when(occupancyMapper.selectOpenBySubjectUnit(SUBJECT_TYPE, SUBJECT_ID, 11L)).thenReturn(target);

        boolean released = service.releaseUnit(SUBJECT_TYPE, SUBJECT_ID, 11L, LocalDate.of(2026, 11, 30), "部分退租");

        assertThat(released).isTrue();
        assertThat(target.getDateTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        verify(occupancyMapper, times(1)).updateById(any(AssetOccupancy.class));
    }

    @Test
    @DisplayName("releaseUnit 对无占用的单元返回 false 且不写库")
    void releaseUnitReturnsFalseWhenNothingOpen() {
        when(occupancyMapper.selectOpenBySubjectUnit(SUBJECT_TYPE, SUBJECT_ID, 11L)).thenReturn(null);

        assertThat(service.releaseUnit(SUBJECT_TYPE, SUBJECT_ID, 11L, FROM, null)).isFalse();
        verify(occupancyMapper, never()).updateById(any(AssetOccupancy.class));
    }

    @Test
    @DisplayName("activateBySubject 只把 reserving 转正，不动已生效行")
    void activateBySubjectFlipsOnlyReserving() {
        AssetOccupancy reserved = occupancy(1L, 11L, OccupancyBizStatus.RESERVING);
        AssetOccupancy active = occupancy(2L, 12L, OccupancyBizStatus.ACTIVE);
        when(occupancyMapper.selectOpenBySubject(SUBJECT_TYPE, SUBJECT_ID))
                .thenReturn(List.of(reserved, active));

        int activated = service.activateBySubject(SUBJECT_TYPE, SUBJECT_ID);

        assertThat(activated).isEqualTo(1);
        assertThat(reserved.getBizStatus()).isEqualTo(OccupancyBizStatus.ACTIVE);
        assertThat(active.getBizStatus()).isEqualTo(OccupancyBizStatus.ACTIVE);
        verify(occupancyMapper, times(1)).updateById(any(AssetOccupancy.class));
    }

    @Test
    @DisplayName("cancelReservation 只收口 reserving，已生效/退租中的占用不受影响")
    void cancelReservationClosesOnlyReserving() {
        AssetOccupancy reserved = occupancy(1L, 11L, OccupancyBizStatus.RESERVING);
        AssetOccupancy vacating = occupancy(2L, 12L, OccupancyBizStatus.VACATING);
        when(occupancyMapper.selectOpenBySubject(SUBJECT_TYPE, SUBJECT_ID))
                .thenReturn(List.of(reserved, vacating));

        LocalDate effective = FROM.plusDays(14);
        int cancelled = service.cancelReservation(SUBJECT_TYPE, SUBJECT_ID, effective, null);

        assertThat(cancelled).isEqualTo(1);
        assertThat(reserved.getDateTo()).isEqualTo(effective);
        assertThat(vacating.getDateTo()).isNull();
    }

    @Test
    @DisplayName("收口日不晚于开始日时按区间约束顺延一天（ck_occupancy_range）")
    void closeDateIsBumpedWhenNotAfterStart() {
        AssetOccupancy reserved = occupancy(1L, 11L, OccupancyBizStatus.RESERVING);
        when(occupancyMapper.selectOpenBySubject(SUBJECT_TYPE, SUBJECT_ID))
                .thenReturn(List.of(reserved));

        service.cancelReservation(SUBJECT_TYPE, SUBJECT_ID, FROM, null);

        assertThat(reserved.getDateTo()).isEqualTo(FROM.plusDays(1));
    }

    @Test
    @DisplayName("markVacating 逐单元标记，已退租中的行不重复计数")
    void markVacatingMarksAllOpenOccupancies() {
        AssetOccupancy active = occupancy(1L, 11L, OccupancyBizStatus.ACTIVE);
        AssetOccupancy already = occupancy(2L, 12L, OccupancyBizStatus.VACATING);
        when(occupancyMapper.selectOpenBySubject(SUBJECT_TYPE, SUBJECT_ID))
                .thenReturn(List.of(active, already));

        int marked = service.markVacating(SUBJECT_ID);

        assertThat(marked).isEqualTo(1);
        assertThat(active.getBizStatus()).isEqualTo(OccupancyBizStatus.VACATING);
        assertThat(already.getBizStatus()).isEqualTo(OccupancyBizStatus.VACATING);
    }

    @Test
    @DisplayName("reserve 写入 reserving 行：exclusive=true、无期限、面积取单元面积（A1）")
    void reserveWritesExclusiveRowWithUnitArea() {
        stubAvailableUnit(new BigDecimal("100.00"));
        when(occupancyMapper.insert(any(AssetOccupancy.class))).thenReturn(1);

        AssetOccupancy created = service.reserve(11L, OccupancyType.CONTRACT, SUBJECT_TYPE,
                SUBJECT_ID, FROM, null, "提交审批");

        assertThat(created.getBizStatus()).isEqualTo(OccupancyBizStatus.RESERVING);
        assertThat(created.getExclusive()).isTrue();
        assertThat(created.getDateTo()).isNull();
        assertThat(created.getArea()).isEqualByComparingTo("100.00");
        assertThat(created.getAssetId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("审批期互斥：同单元已有预留时，第二个申请被拒且不写库（P0-2）")
    void reserveRejectsWhenUnitAlreadyReserved() {
        when(unitMapper.lockForUpdate(11L)).thenReturn(11L);
        when(occupancyMapper.selectOverlapping(anyLong(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(occupancy(9L, 11L, OccupancyBizStatus.RESERVING)));

        assertThatThrownBy(() -> service.reserve(11L, OccupancyType.CONTRACT, SUBJECT_TYPE,
                78L, FROM, null, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已被");

        verify(occupancyMapper, never()).insert(any(AssetOccupancy.class));
    }

    @Test
    @DisplayName("占用类型非法时直接拒绝")
    void reserveRejectsIllegalOccupancyType() {
        assertThatThrownBy(() -> service.reserve(11L, "leasing", SUBJECT_TYPE, SUBJECT_ID, FROM, null, null))
                .isInstanceOf(AppException.class);
        verify(occupancyMapper, never()).insert(any(AssetOccupancy.class));
    }

    private void stubAvailableUnit(BigDecimal area) {
        AssetUnit unit = new AssetUnit();
        unit.setId(11L);
        unit.setAssetId(1L);
        unit.setArea(area);
        when(unitMapper.lockForUpdate(11L)).thenReturn(11L);
        when(unitMapper.selectById(11L)).thenReturn(unit);
        when(occupancyMapper.selectOverlapping(anyLong(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    private AssetOccupancy occupancy(long id, long unitId, String bizStatus) {
        AssetOccupancy occ = new AssetOccupancy();
        occ.setId(id);
        occ.setAssetId(1L);
        occ.setAssetUnitId(unitId);
        occ.setOccupancyType(OccupancyType.CONTRACT);
        occ.setSubjectType(SUBJECT_TYPE);
        occ.setSubjectId(SUBJECT_ID);
        occ.setArea(new BigDecimal("100.00"));
        occ.setDateFrom(FROM);
        occ.setBizStatus(bizStatus);
        occ.setExclusive(true);
        return occ;
    }
}
