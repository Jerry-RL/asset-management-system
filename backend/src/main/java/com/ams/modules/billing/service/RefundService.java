package com.ams.modules.billing.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.entity.RefundOrder;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.billing.mapper.RefundOrderMapper;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.modules.invoice.service.InvoiceService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.compensation.CompensationOrchestrator;
import com.ams.platform.compensation.CompensationOrchestrator.Step;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款冲正闭环（FR-REF-*）：退款申请 → 审批 → 冲正 → 账单/凭证/发票联动。
 *
 * <p><b>冲正为何走补偿编排</b>（[业务闭环编排设计] §4.6）：
 * 三步中「红冲发票」会在事务内调用第三方电子发票平台
 * （{@code InvoiceService.redFlush → invoiceAdapter.redFlush}）。第三方成功后若后续步骤失败，
 * 事务回滚只能还原本库数据，<b>回滚不了第三方已执行的副作用</b>；若再像原先那样吞掉异常，
 * 退款单还会被误置为 completed。因此：
 * <ul>
 *   <li>三步由 {@link CompensationOrchestrator} 记录，失败痕迹写在独立事务中不被回滚；</li>
 *   <li>「红冲发票」声明为 {@code MANUAL}——失败即交人工，不假装可自动回滚；</li>
 *   <li>凭证创建失败不再被忽略，且全部步骤成功后才置 {@code completed}。</li>
 * </ul>
 */
@Service
public class RefundService {

    /** 补偿编排的流程类型标识（{@code process_step.process_type}）。 */
    public static final String PROCESS_REFUND = "refund";

    private static final String STEP_REVERSE_ALLOCATION = "reverse_allocation";
    private static final String STEP_RED_FLUSH_INVOICE = "red_flush_invoice";
    private static final String STEP_CREATE_VOUCHER = "create_voucher";

    private final RefundOrderMapper refundOrderMapper;
    private final PaymentMapper paymentMapper;
    private final AllocationService allocationService;
    private final ApprovalEngine approvalEngine;
    private final InvoiceService invoiceService;
    private final ReconcileService reconcileService;
    private final ObjectMapper objectMapper;
    private final CompensationOrchestrator compensationOrchestrator;

    public RefundService(
            RefundOrderMapper refundOrderMapper,
            PaymentMapper paymentMapper,
            AllocationService allocationService,
            ApprovalEngine approvalEngine,
            InvoiceService invoiceService,
            ReconcileService reconcileService,
            ObjectMapper objectMapper,
            CompensationOrchestrator compensationOrchestrator) {
        this.refundOrderMapper = refundOrderMapper;
        this.paymentMapper = paymentMapper;
        this.allocationService = allocationService;
        this.approvalEngine = approvalEngine;
        this.invoiceService = invoiceService;
        this.reconcileService = reconcileService;
        this.objectMapper = objectMapper;
        this.compensationOrchestrator = compensationOrchestrator;
    }

    @Transactional
    public RefundOrder apply(Long paymentId, BigDecimal amount, String reason, String channel) {
        Payment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "收款记录不存在");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.compareTo(payment.getAmount()) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "退款金额不合法");
        }
        long existing = refundOrderMapper.selectCount(
                new LambdaQueryWrapper<RefundOrder>()
                        .eq(RefundOrder::getPaymentId, paymentId)
                        .ne(RefundOrder::getStatus, "rejected"));
        if (existing > 0) {
            throw new AppException(ErrorCode.CONFLICT, "该收款已存在退款单，不可重复退款");
        }
        RefundOrder order = new RefundOrder();
        order.setPaymentId(paymentId);
        order.setAmount(amount);
        order.setReason(reason);
        order.setChannel(channel);
        order.setStatus("applying");
        refundOrderMapper.insert(order);
        return order;
    }

    @Transactional
    public void submit(Long refundId) {
        RefundOrder order = require(refundId);
        order.setStatus("approving");
        refundOrderMapper.updateById(order);
        approvalEngine.start("refund", refundId);
    }

    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (!"refund".equals(event.getBizType())) {
            return;
        }
        RefundOrder order = refundOrderMapper.selectById(event.getBizId());
        if (order == null || !"approving".equals(order.getStatus())) {
            return;
        }
        if (event.isApproved()) {
            execute(order.getId(), "AUTO-" + order.getId());
        } else {
            order.setStatus("rejected");
            refundOrderMapper.updateById(order);
        }
    }

    @Transactional
    public RefundOrder execute(Long refundId, String thirdPartyRefundNo) {
        RefundOrder order = require(refundId);
        if ("completed".equals(order.getStatus())) {
            return order;
        }
        order.setStatus("executing");
        order.setThirdPartyRefundNo(thirdPartyRefundNo);
        refundOrderMapper.updateById(order);

        Long paymentId = order.getPaymentId();
        Long orderId = order.getId();
        compensationOrchestrator.run(PROCESS_REFUND, orderId, List.of(
                // 本库变更，外层事务回滚即还原
                Step.rollback(1, STEP_REVERSE_ALLOCATION,
                        () -> allocationService.reverseAllocation(paymentId)),
                // 调第三方电子发票平台：成功即外部生效，事务回滚救不回来 → 失败交人工
                Step.manual(2, STEP_RED_FLUSH_INVOICE,
                        () -> invoiceService.redFlushByPayment(paymentId)),
                // 本库写入冲销凭证
                Step.rollback(3, STEP_CREATE_VOUCHER,
                        () -> pushVoucher(order))));

        // 三步全部成功才置完成：任一步失败会抛出，本行不会执行
        order.setStatus("completed");
        refundOrderMapper.updateById(order);
        return order;
    }

    public List<RefundOrder> list(Long paymentId) {
        return refundOrderMapper.selectList(
                new LambdaQueryWrapper<RefundOrder>()
                        .eq(paymentId != null, RefundOrder::getPaymentId, paymentId)
                        .orderByDesc(RefundOrder::getId));
    }

    /**
     * 生成冲销凭证。
     *
     * <p><b>不再吞异常</b>：原先的 {@code catch (Exception ignored)} 会让凭证创建失败时
     * 流程继续、退款单被置为 completed——即「退款已完成但无冲销凭证」的静默不一致，
     * 且毫无痕迹（评审 P0-7）。现在失败即抛出，由补偿编排记录并阻断完成态。
     */
    private void pushVoucher(RefundOrder order) {
        Map<String, Object> content = new HashMap<>();
        content.put("type", "refund");
        content.put("refundId", order.getId());
        content.put("paymentId", order.getPaymentId());
        content.put("amount", order.getAmount());
        String json;
        try {
            json = objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException ex) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "冲销凭证内容序列化失败");
        }
        reconcileService.createVoucher("refund", order.getId(), json);
    }

    private RefundOrder require(Long id) {
        RefundOrder order = refundOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "退款单不存在");
        }
        return order;
    }
}
