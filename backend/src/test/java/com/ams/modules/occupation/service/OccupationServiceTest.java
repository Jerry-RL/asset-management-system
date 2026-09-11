package com.ams.modules.occupation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.OccupancyType;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.service.AssetOccupancyService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.alert.service.AlertService;
import com.ams.modules.occupation.entity.OccupationOrder;
import com.ams.modules.occupation.mapper.OccupationOrderMapper;
import com.ams.modules.revitalization.service.RevitalizationService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.ApprovalCompletedEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 临时占用闭环测试（改造清单触点 5、评审 P0-2 预留态）。
 *
 * <p>锁死两件事：
 * <ol>
 *   <li><b>不再经资产级状态迁移</b>：占用写入 {@code asset_occupancy}（单元粒度），
 *       构造器已移除 {@code LeaseControlService} 依赖，编译期即保证；</li>
 *   <li><b>审批期互斥</b>：提交审批即 {@code reserve}（排他预留），
 *       不再依赖 {@code assertVacant} 这种「先查后写」的服务层纪律。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OccupationServiceTest {

    private static final long ORDER_ID = 11L;
    private static final long ASSET_ID = 100L;
    private static final long UNIT_ID = 7L;

    @Mock
    private OccupationOrderMapper orderMapper;
    @Mock
    private AssetOccupancyService occupancyService;
    @Mock
    private AssetUnitService assetUnitService;
    @Mock
    private ApprovalEngine approvalEngine;
    @Mock
    private RevitalizationService revitalizationService;
    @Mock
    private AlertService alertService;

    private OccupationService service;

    @BeforeEach
    void setUp() {
        service = new OccupationService(orderMapper, occupancyService, assetUnitService,
                approvalEngine, revitalizationService, alertService);
        when(assetUnitService.resolveForLease(ASSET_ID, null)).thenReturn(unit());
    }

    @Test
    @DisplayName("申请：仅要求资产存在可租单元（支持部分占用），不再要求整资产空置")
    void createRequiresLeasableUnitNotWholeAssetVacancy() {
        OccupationOrder order = order("draft");

        OccupationOrder created = service.create(order);

        assertThat(created.getStatus()).isEqualTo("draft");
        verify(assetUnitService).resolveForLease(ASSET_ID, null);
        verify(orderMapper).insert(order);
    }

    @Test
    @DisplayName("申请：资产无计租单元时抛出可读错误，不落库")
    void createRejectsWhenNoUnit() {
        when(assetUnitService.resolveForLease(ASSET_ID, null))
                .thenThrow(new AppException(com.ams.common.exception.ErrorCode.CONFLICT,
                        "资产无可租单元，请先在资产详情中配置计租单元（assetId=100）"));

        assertThatThrownBy(() -> service.create(order("draft")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("无可租单元");
        verify(orderMapper, never()).insert(any(OccupationOrder.class));
    }

    @Test
    @DisplayName("提交审批：按单元登记排他预留（审批期即互斥）")
    void submitRegistersExclusiveReservation() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("draft"));

        service.submit(ORDER_ID);

        verify(occupancyService).reserve(eq(UNIT_ID), eq(OccupancyType.OCCUPATION),
                eq("occupation_order"), eq(ORDER_ID),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 12, 31)), anyString());
        verify(orderMapper).updateById(any(OccupationOrder.class));
        verify(approvalEngine).start("occupation", ORDER_ID);
    }

    @Test
    @DisplayName("提交审批冲突：单元已被占则抛错，单据保持 draft 不进入审批")
    void submitConflictLeavesOrderInDraft() {
        OccupationOrder order = order("draft");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(occupancyService.reserve(anyLong(), anyString(), anyString(), anyLong(),
                any(), any(), anyString()))
                .thenThrow(new AppException(com.ams.common.exception.ErrorCode.CONFLICT,
                        "该单元已被【合同占用】占用"));

        assertThatThrownBy(() -> service.submit(ORDER_ID)).isInstanceOf(AppException.class);

        verify(orderMapper, never()).updateById(any(OccupationOrder.class));
        verify(approvalEngine, never()).start(anyString(), anyLong());
    }

    @Test
    @DisplayName("审批通过：预留转生效，不重复建占用")
    void approveActivatesReservation() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("approving"));
        when(occupancyService.activateBySubject("occupation_order", ORDER_ID)).thenReturn(1);

        OccupationOrder approved = service.approve(ORDER_ID);

        assertThat(approved.getStatus()).isEqualTo("occupied");
        verify(occupancyService, never()).occupy(anyLong(), anyString(), anyString(), anyLong(),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("跳过提交直接审批：补建生效占用，避免「单据已占用、占用表无记录」")
    void approveWithoutReservationBackfillsOccupancy() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("draft"));
        when(occupancyService.activateBySubject("occupation_order", ORDER_ID)).thenReturn(0);

        service.approve(ORDER_ID);

        verify(occupancyService).occupy(eq(UNIT_ID), eq(OccupancyType.OCCUPATION),
                eq("occupation_order"), eq(ORDER_ID),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 12, 31)),
                isNull(), anyString());
    }

    @Test
    @DisplayName("解除占用：按单据全量收口区间（不删行）")
    void releaseClosesOccupancyBySubject() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("occupied"));

        OccupationOrder released = service.release(ORDER_ID);

        assertThat(released.getStatus()).isEqualTo("released");
        verify(occupancyService).releaseBySubject("occupation_order", ORDER_ID, null, "解除占用");
        verify(revitalizationService).createOnVacant(ASSET_ID, "占用解除", "招租盘活");
    }

    @Test
    @DisplayName("审批驳回事件：自动收口预留，单据回到草稿（消除预留泄漏）")
    void rejectedApprovalEventReleasesReservation() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("approving"));

        service.onApprovalCompleted(new ApprovalCompletedEvent("occupation", ORDER_ID, false));

        verify(occupancyService).cancelReservation(eq("occupation_order"), eq(ORDER_ID),
                any(LocalDate.class), anyString());
        verify(orderMapper).updateById(any(OccupationOrder.class));
    }

    @Test
    @DisplayName("审批通过事件：不自动推进（仍由 /approve 端点负责），避免重复处理")
    void approvedEventIsIgnored() {
        service.onApprovalCompleted(new ApprovalCompletedEvent("occupation", ORDER_ID, true));

        verify(occupancyService, never()).cancelReservation(anyString(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("其他业务类型的审批事件不影响本域")
    void otherBizTypeIsIgnored() {
        service.onApprovalCompleted(new ApprovalCompletedEvent("contract", ORDER_ID, false));

        verify(occupancyService, never()).cancelReservation(anyString(), anyLong(), any(), anyString());
    }

    private OccupationOrder order(String status) {
        OccupationOrder order = new OccupationOrder();
        order.setId(ORDER_ID);
        order.setAssetId(ASSET_ID);
        order.setStartDate(LocalDate.of(2026, 10, 1));
        order.setEndDate(LocalDate.of(2026, 12, 31));
        order.setArea(new BigDecimal("100.00"));
        order.setStatus(status);
        return order;
    }

    private AssetUnit unit() {
        AssetUnit unit = new AssetUnit();
        unit.setId(UNIT_ID);
        unit.setAssetId(ASSET_ID);
        unit.setUnitNo("U-1");
        unit.setArea(new BigDecimal("100.00"));
        return unit;
    }
}
