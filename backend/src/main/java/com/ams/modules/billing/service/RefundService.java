package com.ams.modules.billing.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.entity.RefundOrder;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.billing.mapper.RefundOrderMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款冲正闭环（FR-REF-*，§4.24.7）：退款申请 → 审批 → 冲正 → 账单/凭证/发票联动。
 */
@Service
public class RefundService {

    private final RefundOrderMapper refundOrderMapper;
    private final PaymentMapper paymentMapper;
    private final AllocationService allocationService;
    private final ApprovalEngine approvalEngine;

    public RefundService(
            RefundOrderMapper refundOrderMapper,
            PaymentMapper paymentMapper,
            AllocationService allocationService,
            ApprovalEngine approvalEngine) {
        this.refundOrderMapper = refundOrderMapper;
        this.paymentMapper = paymentMapper;
        this.allocationService = allocationService;
        this.approvalEngine = approvalEngine;
    }

    /** 退款申请（FR-REF-001）。 */
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
        // 同一收款不可重复退款（已存在非驳回退款单则阻断）
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

    /** 提交退款审批（FR-REF-002）。 */
    @Transactional
    public void submit(Long refundId) {
        RefundOrder order = require(refundId);
        order.setStatus("approving");
        refundOrderMapper.updateById(order);
        approvalEngine.start("refund", refundId);
    }

    /** 冲正执行（FR-REF-003）：逆序回退账单状态。 */
    @Transactional
    public RefundOrder execute(Long refundId, String thirdPartyRefundNo) {
        RefundOrder order = require(refundId);
        order.setStatus("executing");
        order.setThirdPartyRefundNo(thirdPartyRefundNo);
        refundOrderMapper.updateById(order);

        // 逆序回退核销
        allocationService.reverseAllocation(order.getPaymentId());

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

    private RefundOrder require(Long id) {
        RefundOrder order = refundOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "退款单不存在");
        }
        return order;
    }
}
