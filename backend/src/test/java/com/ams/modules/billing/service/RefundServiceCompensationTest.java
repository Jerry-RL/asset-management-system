package com.ams.modules.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.billing.entity.RefundOrder;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.billing.mapper.RefundOrderMapper;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.modules.invoice.service.InvoiceService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.compensation.CompensateKind;
import com.ams.platform.compensation.CompensationOrchestrator;
import com.ams.platform.compensation.ProcessStep;
import com.ams.platform.compensation.ProcessStepRecorder;
import com.ams.platform.compensation.ProcessStepStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 退款冲正补偿测试（评审 P0-7）。
 *
 * <p>覆盖三个曾被实现掩盖的问题：
 * <ol>
 *   <li>凭证创建失败被 {@code catch (Exception ignored)} 静默吞掉，退款单仍被置为 {@code completed}
 *       → 「已完成但无冲销凭证」的不一致；</li>
 *   <li>任一步失败时无任何痕迹（无步骤记录）；</li>
 *   <li>第三方红冲是外部副作用，失败不可回滚，必须交人工而非假装回滚。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceCompensationTest {

    private static final long REFUND_ID = 55L;
    private static final long PAYMENT_ID = 77L;

    @Mock
    private RefundOrderMapper refundOrderMapper;
    @Mock
    private PaymentMapper paymentMapper;
    @Mock
    private AllocationService allocationService;
    @Mock
    private ApprovalEngine approvalEngine;
    @Mock
    private InvoiceService invoiceService;
    @Mock
    private ReconcileService reconcileService;
    @Mock
    private ProcessStepRecorder recorder;

    private RefundService refundService;
    private RefundOrder order;

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例：要覆盖序列化路径，而非把它 mock 掉
        refundService = new RefundService(refundOrderMapper, paymentMapper, allocationService,
                approvalEngine, invoiceService, reconcileService, new ObjectMapper(),
                new CompensationOrchestrator(recorder));

        order = new RefundOrder();
        order.setId(REFUND_ID);
        order.setPaymentId(PAYMENT_ID);
        order.setAmount(new BigDecimal("1000.00"));
        order.setStatus("approving");
        when(refundOrderMapper.selectById(REFUND_ID)).thenReturn(order);
        stubRecorder();
    }

    @Test
    @DisplayName("三步全部成功：退款单置 completed，三步均有记录")
    void happyPathCompletesOrder() {
        RefundOrder result = refundService.execute(REFUND_ID, "AUTO-55");

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getThirdPartyRefundNo()).isEqualTo("AUTO-55");
        verify(allocationService, times(1)).reverseAllocation(PAYMENT_ID);
        verify(invoiceService, times(1)).redFlushByPayment(PAYMENT_ID);
        verify(reconcileService, times(1)).createVoucher(eq("refund"), eq(REFUND_ID), anyString());
        verify(recorder, times(3)).markDone(any(ProcessStep.class));
        verify(recorder, never()).markManual(any(), any());
        verify(recorder, never()).markRolledBack(any());
    }

    @Test
    @DisplayName("凭证创建失败不再被吞：异常上抛、退款单保持 executing（不假装完成）")
    void voucherFailureIsNotSwallowed() {
        when(reconcileService.createVoucher(anyString(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("凭证库不可用"));

        assertThatThrownBy(() -> refundService.execute(REFUND_ID, "AUTO-55"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("凭证库不可用");

        // 关键回归：原先 catch(Exception ignored) 会让流程继续并置 completed
        assertThat(order.getStatus()).isEqualTo("executing");
        verify(recorder).markFailed(any(ProcessStep.class), any());
        // 前序两步被改判且痕迹保留：DB 内步骤回滚、外部副作用步骤交人工
        verify(recorder, times(1)).markRolledBack(any(ProcessStep.class));
        verify(recorder, times(1)).markManual(any(ProcessStep.class), anyString());
    }

    @Test
    @DisplayName("第三方红冲失败：记为 manual（外部副作用不可回滚），并阻断完成态")
    void externalRedFlushFailureIsManual() {
        doThrow(new IllegalStateException("电子发票平台超时"))
                .when(invoiceService).redFlushByPayment(PAYMENT_ID);

        assertThatThrownBy(() -> refundService.execute(REFUND_ID, "AUTO-55"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("电子发票平台超时");

        assertThat(order.getStatus()).isEqualTo("executing");
        verify(recorder).markManual(any(ProcessStep.class),
                org.mockito.ArgumentMatchers.contains("电子发票平台超时"));
        // 第一步是 DB 内步骤 → 回滚；第三步未执行
        verify(recorder).markRolledBack(any(ProcessStep.class));
        verify(reconcileService, never()).createVoucher(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("退回核销失败：不触碰发票，异常上抛")
    void reverseAllocationFailureStopsBeforeInvoice() {
        doThrow(new AppException(com.ams.common.exception.ErrorCode.CONFLICT, "核销流水已锁定"))
                .when(allocationService).reverseAllocation(PAYMENT_ID);

        assertThatThrownBy(() -> refundService.execute(REFUND_ID, "AUTO-55"))
                .isInstanceOf(AppException.class)
                .hasMessage("核销流水已锁定");

        assertThat(order.getStatus()).isEqualTo("executing");
        verify(invoiceService, never()).redFlushByPayment(anyLong());
        verify(recorder).markFailed(any(ProcessStep.class), any());
    }

    @Test
    @DisplayName("已完成退款单重复执行：幂等返回，不重复冲正")
    void alreadyCompletedIsIdempotent() {
        order.setStatus("completed");

        RefundOrder result = refundService.execute(REFUND_ID, "AUTO-55");

        assertThat(result.getStatus()).isEqualTo("completed");
        verify(allocationService, never()).reverseAllocation(anyLong());
        verify(recorder, never()).begin(anyString(), anyLong(), anyInt(), anyString(), any());
    }

    private void stubRecorder() {
        when(recorder.begin(anyString(), anyLong(), anyInt(), anyString(), any(CompensateKind.class)))
                .thenAnswer(invocation -> {
                    ProcessStep record = new ProcessStep();
                    record.setId((long) (int) invocation.getArgument(2));
                    record.setProcessType(invocation.getArgument(0));
                    record.setBizId(invocation.getArgument(1));
                    record.setStepNo(invocation.getArgument(2));
                    record.setStepName(invocation.getArgument(3));
                    record.setStatus(ProcessStepStatus.RUNNING);
                    return record;
                });
    }
}
