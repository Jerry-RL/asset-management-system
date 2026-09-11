package com.ams.modules.selfuse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.modules.asset.OccupancyType;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.service.AssetOccupancyService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.alert.service.AlertService;
import com.ams.modules.revitalization.service.RevitalizationService;
import com.ams.modules.selfuse.entity.SelfUseOrder;
import com.ams.modules.selfuse.mapper.SelfUseOrderMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.ApprovalCompletedEvent;
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
 * 资产自用闭环测试（改造清单触点 6、评审 P0-2 预留态）。
 *
 * <p>与 {@code OccupationServiceTest} 同构：占用写单元粒度、审批期预留、
 * 跳过提交直接审批时补建占用。自用与临时占用的唯一差别是 {@code OccupancyType} 与单据类型。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfUseServiceTest {

    private static final long ORDER_ID = 21L;
    private static final long ASSET_ID = 200L;
    private static final long UNIT_ID = 9L;

    @Mock
    private SelfUseOrderMapper orderMapper;
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

    private SelfUseService service;

    @BeforeEach
    void setUp() {
        service = new SelfUseService(orderMapper, occupancyService, assetUnitService,
                approvalEngine, revitalizationService, alertService);
        when(assetUnitService.resolveForLease(ASSET_ID, null)).thenReturn(unit());
    }

    @Test
    @DisplayName("申请：校验可租单元并落库为草稿")
    void createResolvesUnitAndPersistsDraft() {
        SelfUseOrder order = order("draft");

        SelfUseOrder created = service.create(order);

        assertThat(created.getStatus()).isEqualTo("draft");
        verify(assetUnitService).resolveForLease(ASSET_ID, null);
        verify(orderMapper).insert(order);
    }

    @Test
    @DisplayName("提交审批：以 self_use 类型登记排他预留")
    void submitReservesWithSelfUseType() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("draft"));

        service.submit(ORDER_ID);

        verify(occupancyService).reserve(eq(UNIT_ID), eq(OccupancyType.SELF_USE),
                eq("self_use_order"), eq(ORDER_ID),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 12, 31)), anyString());
        verify(approvalEngine).start("self_use", ORDER_ID);
    }

    @Test
    @DisplayName("审批通过：预留转生效")
    void approveActivatesReservation() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("approving"));
        when(occupancyService.activateBySubject("self_use_order", ORDER_ID)).thenReturn(1);

        SelfUseOrder approved = service.approve(ORDER_ID);

        assertThat(approved.getStatus()).isEqualTo("self_use");
        verify(occupancyService, never()).occupy(anyLong(), anyString(), anyString(), anyLong(),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("跳过提交直接审批：补建生效占用")
    void approveWithoutReservationBackfillsOccupancy() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("draft"));
        when(occupancyService.activateBySubject("self_use_order", ORDER_ID)).thenReturn(0);

        service.approve(ORDER_ID);

        verify(occupancyService).occupy(eq(UNIT_ID), eq(OccupancyType.SELF_USE),
                eq("self_use_order"), eq(ORDER_ID),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 12, 31)),
                isNull(), anyString());
    }

    @Test
    @DisplayName("结束自用：按单据全量收口，并触发盘活")
    void endClosesOccupancyAndTriggersRevitalization() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("self_use"));

        SelfUseOrder ended = service.end(ORDER_ID);

        assertThat(ended.getStatus()).isEqualTo("ended");
        verify(occupancyService).releaseBySubject("self_use_order", ORDER_ID, null, "结束自用");
        verify(revitalizationService).createOnVacant(ASSET_ID, "自用结束", "招租盘活");
    }

    @Test
    @DisplayName("撤销申请：收口预留（原实现无此路径）")
    void withdrawCancelsReservation() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("approving"));

        service.withdraw(ORDER_ID, "审批驳回");

        verify(occupancyService).cancelReservation(eq("self_use_order"), eq(ORDER_ID),
                any(LocalDate.class), eq("审批驳回"));
    }

    @Test
    @DisplayName("审批驳回事件：自动收口预留（消除预留泄漏）")
    void rejectedApprovalEventReleasesReservation() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("approving"));

        service.onApprovalCompleted(new ApprovalCompletedEvent("self_use", ORDER_ID, false));

        verify(occupancyService).cancelReservation(eq("self_use_order"), eq(ORDER_ID),
                any(LocalDate.class), anyString());
    }

    @Test
    @DisplayName("审批通过事件与其他业务类型：均不触发收口")
    void approvedAndForeignEventsAreIgnored() {
        service.onApprovalCompleted(new ApprovalCompletedEvent("self_use", ORDER_ID, true));
        service.onApprovalCompleted(new ApprovalCompletedEvent("contract", ORDER_ID, false));

        verify(occupancyService, never()).cancelReservation(anyString(), anyLong(), any(), anyString());
    }

    private SelfUseOrder order(String status) {
        SelfUseOrder order = new SelfUseOrder();
        order.setId(ORDER_ID);
        order.setAssetId(ASSET_ID);
        order.setStartDate(LocalDate.of(2026, 10, 1));
        order.setEndDate(LocalDate.of(2026, 12, 31));
        order.setStatus(status);
        return order;
    }

    private AssetUnit unit() {
        AssetUnit unit = new AssetUnit();
        unit.setId(UNIT_ID);
        unit.setAssetId(ASSET_ID);
        unit.setUnitNo("U-2");
        return unit;
    }
}
