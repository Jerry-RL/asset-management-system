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
import com.ams.platform.event.ApprovalCompletedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 */
@Service
public class RefundService {

    private final RefundOrderMapper refundOrderMapper;
    private final PaymentMapper paymentMapper;
    private final AllocationService allocationService;
    private final ApprovalEngine approvalEngine;
    private final InvoiceService invoiceService;
    private final ReconcileService reconcileService;
    private final ObjectMapper objectMapper;

    public RefundService(
            RefundOrderMapper refundOrderMapper,
            PaymentMapper paymentMapper,
            AllocationService allocationService,
            ApprovalEngine approvalEngine,
            InvoiceService invoiceService,
            ReconcileService reconcileService,
            ObjectMapper objectMapper) {
        this.refundOrderMapper = refundOrderMapper;
        this.paymentMapper = paymentMapper;
        this.allocationService = allocationService;
        this.approvalEngine = approvalEngine;
        this.invoiceService = invoiceService;
        this.reconcileService = reconcileService;
        this.objectMapper = objectMapper;
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

        allocationService.reverseAllocation(order.getPaymentId());
        invoiceService.redFlushByPayment(order.getPaymentId());
        pushVoucher(order);

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

    private void pushVoucher(RefundOrder order) {
        try {
            Map<String, Object> content = new HashMap<>();
            content.put("type", "refund");
            content.put("refundId", order.getId());
            content.put("paymentId", order.getPaymentId());
            content.put("amount", order.getAmount());
            reconcileService.createVoucher("refund", order.getId(), objectMapper.writeValueAsString(content));
        } catch (Exception ignored) {
            // ignore
        }
    }

    private RefundOrder require(Long id) {
        RefundOrder order = refundOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "退款单不存在");
        }
        return order;
    }
}
