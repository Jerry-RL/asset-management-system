package com.ams.modules.billing.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.BillPayment;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.billing.mapper.BillPaymentMapper;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收款核销与分配（FR-PAY-*，SRS §4.23.29）。
 *
 * 核心算法：
 *  - 一笔收款按策略（默认 FIFO）匹配多张历史账单，支持全额与部分核销。
 *  - 同一账单「本金 + 滞纳金」可配置核销顺序（默认先滞纳金后本金，FR-PAY-003）。
 *  - 核销流水不可变（bill_payment），冲正按 id 逆序回退。
 *  - 超额转预收或退回（默认转预收）。
 */
@Service
public class AllocationService {

    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;
    private final BillPaymentMapper billPaymentMapper;
    private final PrepayService prepayService;

    public AllocationService(
            BillMapper billMapper,
            PaymentMapper paymentMapper,
            BillPaymentMapper billPaymentMapper,
            PrepayService prepayService) {
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
        this.billPaymentMapper = billPaymentMapper;
        this.prepayService = prepayService;
    }

    /**
     * 收款入账并核销（FIFO 默认）。
     * @return 未核销余额（超额部分）
     */
    @Transactional
    public BigDecimal registerAndAllocate(Payment payment, String strategy) {
        paymentMapper.insert(payment);
        return allocate(payment, strategy);
    }

    /**
     * 对一笔已入账收款执行核销分配。
     * @return 未核销余额（超额部分，转预收）
     */
    @Transactional
    public BigDecimal allocate(Payment payment, String strategy) {
        BigDecimal remain = payment.getAmount();
        if (remain == null || remain.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        Long contractId = payment.getContractId();
        if (contractId == null) {
            return remain;
        }
        // 未结清账单（本金或滞纳金未结清），FIFO 按到期日升序
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getContractId, contractId)
                        .and(w -> w.ne(Bill::getStatus, BillStatus.VOIDED))
                        .orderByAsc(Bill::getDueDate));

        for (Bill bill : bills) {
            if (remain.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal principalDue = bill.getAmount().subtract(bill.getPaidAmount());
            BigDecimal lateFeeDue = bill.getLateFeeAmount().subtract(bill.getLateFeePaidAmount());
            BigDecimal totalDue = principalDue.add(lateFeeDue);
            if (totalDue.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // 先滞纳金后本金（FR-PAY-003 默认）
            BigDecimal lateApplied = min(remain, lateFeeDue);
            if (lateApplied.compareTo(BigDecimal.ZERO) > 0) {
                insertAllocation(bill, payment, "late_fee", lateApplied);
                bill.setLateFeePaidAmount(bill.getLateFeePaidAmount().add(lateApplied));
                remain = remain.subtract(lateApplied);
            }
            if (remain.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal principalApplied = min(remain, principalDue);
                if (principalApplied.compareTo(BigDecimal.ZERO) > 0) {
                    insertAllocation(bill, payment, "principal", principalApplied);
                    bill.setPaidAmount(bill.getPaidAmount().add(principalApplied));
                    remain = remain.subtract(principalApplied);
                }
            }
            // 账单状态回写
            bill.setStatus(resolveBillStatus(bill));
            billMapper.updateById(bill);
        }

        // 超额转预收（默认动作可配）
        if (remain.compareTo(BigDecimal.ZERO) > 0) {
            prepayService.add(contractId, payment.getTenantId(), remain, "收款超额转预收");
        }
        return remain;
    }

    /**
     * 冲正逆序回退（FR-REF-003）：按原核销记录 id 逆序回退账单状态。
     */
    @Transactional
    public void reverseAllocation(Long paymentId) {
        List<BillPayment> allocs = billPaymentMapper.selectList(
                new LambdaQueryWrapper<BillPayment>()
                        .eq(BillPayment::getPaymentId, paymentId)
                        .orderByDesc(BillPayment::getId));
        for (BillPayment alloc : allocs) {
            Bill bill = billMapper.selectById(alloc.getBillId());
            if (bill == null) {
                continue;
            }
            if ("principal".equals(alloc.getAmountType())) {
                bill.setPaidAmount(bill.getPaidAmount().subtract(alloc.getAmount()));
            } else {
                bill.setLateFeePaidAmount(bill.getLateFeePaidAmount().subtract(alloc.getAmount()));
            }
            bill.setStatus(resolveBillStatus(bill));
            billMapper.updateById(bill);
        }
        // 核销流水不可删改：保留原流水，仅回退账单状态（流水作为审计证据）
    }

    public List<BillPayment> listAllocations(Long paymentId) {
        return billPaymentMapper.selectList(
                new LambdaQueryWrapper<BillPayment>()
                        .eq(BillPayment::getPaymentId, paymentId)
                        .orderByAsc(BillPayment::getId));
    }

    private void insertAllocation(Bill bill, Payment payment, String type, BigDecimal amount) {
        BillPayment alloc = new BillPayment();
        alloc.setBillId(bill.getId());
        alloc.setPaymentId(payment.getId());
        alloc.setAmountType(type);
        alloc.setAmount(amount);
        alloc.setAllocatedAt(LocalDateTime.now());
        alloc.setAllocatedBy(SecurityUtils.currentUserIdOrNull());
        billPaymentMapper.insert(alloc);
    }

    private String resolveBillStatus(Bill bill) {
        BigDecimal principalDue = bill.getAmount().subtract(bill.getPaidAmount());
        BigDecimal lateFeeDue = bill.getLateFeeAmount().subtract(bill.getLateFeePaidAmount());
        if (principalDue.compareTo(BigDecimal.ZERO) <= 0 && lateFeeDue.compareTo(BigDecimal.ZERO) <= 0) {
            return BillStatus.PAID;
        }
        if (bill.getPaidAmount().compareTo(BigDecimal.ZERO) > 0
                || bill.getLateFeePaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            return BillStatus.PARTIAL_PAID;
        }
        return BillStatus.UNPAID;
    }

    private BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
