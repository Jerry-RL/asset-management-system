package com.ams.modules.disposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.service.PaymentService;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.disposal.service.DisposalService;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.modules.record.DisposalCascadePort;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「已处置的资产仍能登记处置」的链路守卫。
 *
 * <p>背景：{@code exited} 是租控终态且不允许自迁移，而 V57 起「处置完成」与「项目 / 分区级
 * 级联」都会把资产置为已退出。若 {@code onApproved} / {@code complete} 照旧调
 * {@link LeaseControlService#transition}，第二次处置会在审批通过那一步 409，
 * 使用者看到的是「资产一旦被处置，就无法再登记处置记录」。
 *
 * <p>因此这里钉住两件事：<b>两处租控写入都必须走终态豁免入口</b>（而不是 `transition`），
 * 且<b>豁免返回 false 时业务主流程不得中断</b>（单据要能走完，资产状态由级联负责）。
 */
class DisposalReDisposalTest {

    private static final long ORDER_ID = 55L;
    private static final long ASSET_ID = 7L;

    private final DisposalOrderMapper disposalOrderMapper = mock(DisposalOrderMapper.class);
    private final LeaseControlService leaseControlService = mock(LeaseControlService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final ReconcileService reconcileService = mock(ReconcileService.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final DisposalCascadePort disposalCascadePort = mock(DisposalCascadePort.class);

    private final DisposalService service = new DisposalService(
            disposalOrderMapper,
            leaseControlService,
            mock(ApprovalEngine.class),
            mock(CertificateService.class),
            paymentService,
            reconcileService,
            objectMapper,
            mock(DomainEventPublisher.class),
            mock(RecordSheetService.class),
            disposalCascadePort);

    @Test
    @DisplayName("审批通过：走终态豁免入口（DISPOSING），资产已 exited 时不 409")
    void approvalUsesTerminalExemptTransition() {
        when(disposalOrderMapper.selectById(ORDER_ID)).thenReturn(order("approving"));
        // 豁免返回 false = 资产已是 exited → 租控跳过，但审批通过本身必须成功
        when(leaseControlService.transitionUnlessExited(
                any(), any(), any(), any(), any())).thenReturn(false);

        service.onApproved(ORDER_ID);

        verify(leaseControlService).transitionUnlessExited(
                eq(ASSET_ID), eq(LeaseControlStatus.DISPOSING), eq("disposal"), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("完成：走终态豁免入口（EXITED），且租控被跳过时仍写出台账")
    void completionUsesTerminalExemptTransitionAndStillCascades() throws Exception {
        DisposalOrder order = order("executing");
        order.setActualAmount(new BigDecimal("120.50"));
        when(disposalOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(paymentService.registerConfirmed(any(), any(), any(), any(), any()))
                .thenReturn(new Payment());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reconcileService.createVoucher(any(), any(), any())).thenReturn(new FinanceVoucher());
        when(leaseControlService.transitionUnlessExited(
                any(), any(), any(), any(), any())).thenReturn(false);

        DisposalOrder completed = service.complete(ORDER_ID);

        assertThat(completed.getStatus()).isEqualTo("completed");
        verify(leaseControlService).transitionUnlessExited(
                eq(ASSET_ID), eq(LeaseControlStatus.EXITED), eq("disposal"), eq(ORDER_ID), any());
        // 租控被跳过（资产早已退出）与「台账不受影响」是两件事：台账必须照写
        verify(disposalCascadePort).cascadeDispose(
                eq(DisposalCascadePort.TARGET_ASSET), eq(ASSET_ID), any(), eq(ORDER_ID), isNull());
    }

    @Test
    @DisplayName("豁免返回 false 不改变业务语义：单据照旧走到 completed")
    void skippedTransitionDoesNotBreakCompletion() throws Exception {
        DisposalOrder order = order("executing");
        when(disposalOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reconcileService.createVoucher(any(), any(), any())).thenReturn(new FinanceVoucher());
        when(leaseControlService.transitionUnlessExited(
                any(), any(), any(), any(), any())).thenReturn(false);

        // actualAmount 为 null → 不触发收款（只走凭证分支），租控跳过也不得中断
        assertThat(service.complete(ORDER_ID).getStatus()).isEqualTo("completed");
    }

    private DisposalOrder order(String status) {
        DisposalOrder order = new DisposalOrder();
        order.setId(ORDER_ID);
        order.setAssetId(ASSET_ID);
        order.setStatus(status);
        order.setDisposalType("sale");
        return order;
    }
}
