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
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收款核销与分配（FR-PAY-*，SRS §4.23.29）。
 *
 * <p>策略：fifo / specified / proportional；默认先滞纳金后本金（FR-PAY-003）。
 */
@Service
public class AllocationService {

    private final BillMapper billMapper;
    private final PaymentMapper paymentMapper;
    private final BillPaymentMapper billPaymentMapper;
    private final PrepayService prepayService;
    private final ContractMapper contractMapper;

    public AllocationService(
            BillMapper billMapper,
            PaymentMapper paymentMapper,
            BillPaymentMapper billPaymentMapper,
            PrepayService prepayService,
            ContractMapper contractMapper) {
        this.billMapper = billMapper;
        this.paymentMapper = paymentMapper;
        this.billPaymentMapper = billPaymentMapper;
        this.prepayService = prepayService;
        this.contractMapper = contractMapper;
    }

    @Transactional
    public BigDecimal registerAndAllocate(Payment payment, String strategy) {
        paymentMapper.insert(payment);
        return allocate(payment, strategy);
    }

    @Transactional
    public BigDecimal allocate(Payment payment, String strategy) {
        return allocate(payment, strategy, null, null);
    }

    /**
     * @param strategy fifo | specified | proportional
     * @param billIds specified 时必填；其他策略可选过滤
     * @param lateFeeFirst null 时读取合同配置，默认 true
     */
    @Transactional
    public BigDecimal allocate(Payment payment, String strategy, List<Long> billIds, Boolean lateFeeFirst) {
        BigDecimal remain = payment.getAmount();
        if (remain == null || remain.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        Long contractId = payment.getContractId();
        if (contractId == null) {
            return remain;
        }

        String resolvedStrategy = strategy == null || strategy.isBlank() ? "fifo" : strategy.trim().toLowerCase();
        boolean lateFirst = resolveLateFeeFirst(contractId, lateFeeFirst);

        List<Bill> candidates = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getContractId, contractId)
                        .ne(Bill::getStatus, BillStatus.VOIDED)
                        .orderByAsc(Bill::getDueDate)
                        .orderByAsc(Bill::getId));

        if (billIds != null && !billIds.isEmpty()) {
            Set<Long> idSet = billIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
            candidates = candidates.stream().filter(b -> idSet.contains(b.getId())).toList();
        }

        List<Bill> openBills = candidates.stream()
                .filter(b -> totalDue(b).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(Bill::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Bill::getId))
                .toList();

        if (openBills.isEmpty()) {
            if (remain.compareTo(BigDecimal.ZERO) > 0) {
                prepayService.add(contractId, payment.getTenantId(), remain, "收款超额转预收");
            }
            return remain;
        }

        switch (resolvedStrategy) {
            case "specified" -> {
                if (billIds == null || billIds.isEmpty()) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "指定核销须提供 billIds");
                }
                remain = allocateSequentially(openBills, payment, remain, lateFirst);
            }
            case "proportional" -> remain = allocateProportional(openBills, payment, remain, lateFirst);
            default -> remain = allocateSequentially(openBills, payment, remain, lateFirst); // fifo
        }

        if (remain.compareTo(BigDecimal.ZERO) > 0) {
            prepayService.add(contractId, payment.getTenantId(), remain, "收款超额转预收");
        }
        return remain;
    }

    /** 冲正后按新策略重新核销。 */
    @Transactional
    public BigDecimal reallocate(Long paymentId, String strategy, List<Long> billIds) {
        Payment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "收款不存在");
        }
        reverseAllocation(paymentId);
        return allocate(payment, strategy, billIds, null);
    }

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
                bill.setPaidAmount(nz(bill.getPaidAmount()).subtract(alloc.getAmount()));
            } else {
                bill.setLateFeePaidAmount(nz(bill.getLateFeePaidAmount()).subtract(alloc.getAmount()));
            }
            bill.setStatus(resolveBillStatus(bill));
            billMapper.updateById(bill);
        }
    }

    public List<BillPayment> listAllocations(Long paymentId) {
        return billPaymentMapper.selectList(
                new LambdaQueryWrapper<BillPayment>()
                        .eq(BillPayment::getPaymentId, paymentId)
                        .orderByAsc(BillPayment::getId));
    }

    private BigDecimal allocateSequentially(List<Bill> bills, Payment payment, BigDecimal remain,
            boolean lateFirst) {
        for (Bill bill : bills) {
            if (remain.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            remain = applyToBill(bill, payment, remain, lateFirst);
        }
        return remain;
    }

    private BigDecimal allocateProportional(List<Bill> bills, Payment payment, BigDecimal remain,
            boolean lateFirst) {
        BigDecimal totalDue = bills.stream().map(this::totalDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDue.compareTo(BigDecimal.ZERO) <= 0) {
            return remain;
        }
        BigDecimal pool = remain.min(totalDue);
        BigDecimal allocatedSum = BigDecimal.ZERO;
        List<BigDecimal> shares = new ArrayList<>();
        for (int i = 0; i < bills.size(); i++) {
            BigDecimal due = totalDue(bills.get(i));
            BigDecimal share;
            if (i == bills.size() - 1) {
                share = pool.subtract(allocatedSum);
            } else {
                share = pool.multiply(due).divide(totalDue, 2, RoundingMode.HALF_UP);
                allocatedSum = allocatedSum.add(share);
            }
            shares.add(share.max(BigDecimal.ZERO));
        }
        BigDecimal leftover = remain.subtract(pool);
        for (int i = 0; i < bills.size(); i++) {
            applyToBill(bills.get(i), payment, shares.get(i), lateFirst);
        }
        return leftover.max(BigDecimal.ZERO);
    }

    private BigDecimal applyToBill(Bill bill, Payment payment, BigDecimal remain, boolean lateFirst) {
        BigDecimal principalDue = nz(bill.getAmount()).subtract(nz(bill.getPaidAmount())).subtract(nz(bill.getReducedAmount()));
        BigDecimal lateFeeDue = nz(bill.getLateFeeAmount()).subtract(nz(bill.getLateFeePaidAmount()));
        if (principalDue.add(lateFeeDue).compareTo(BigDecimal.ZERO) <= 0 || remain.compareTo(BigDecimal.ZERO) <= 0) {
            return remain;
        }
        if (lateFirst) {
            BigDecimal lateApplied = min(remain, lateFeeDue);
            if (lateApplied.compareTo(BigDecimal.ZERO) > 0) {
                insertAllocation(bill, payment, "late_fee", lateApplied);
                bill.setLateFeePaidAmount(nz(bill.getLateFeePaidAmount()).add(lateApplied));
                remain = remain.subtract(lateApplied);
            }
            if (remain.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal principalApplied = min(remain, principalDue);
                if (principalApplied.compareTo(BigDecimal.ZERO) > 0) {
                    insertAllocation(bill, payment, "principal", principalApplied);
                    bill.setPaidAmount(nz(bill.getPaidAmount()).add(principalApplied));
                    remain = remain.subtract(principalApplied);
                }
            }
        } else {
            BigDecimal principalApplied = min(remain, principalDue);
            if (principalApplied.compareTo(BigDecimal.ZERO) > 0) {
                insertAllocation(bill, payment, "principal", principalApplied);
                bill.setPaidAmount(nz(bill.getPaidAmount()).add(principalApplied));
                remain = remain.subtract(principalApplied);
            }
            if (remain.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal lateApplied = min(remain, lateFeeDue);
                if (lateApplied.compareTo(BigDecimal.ZERO) > 0) {
                    insertAllocation(bill, payment, "late_fee", lateApplied);
                    bill.setLateFeePaidAmount(nz(bill.getLateFeePaidAmount()).add(lateApplied));
                    remain = remain.subtract(lateApplied);
                }
            }
        }
        bill.setStatus(resolveBillStatus(bill));
        billMapper.updateById(bill);
        return remain;
    }

    private boolean resolveLateFeeFirst(Long contractId, Boolean override) {
        if (override != null) {
            return override;
        }
        Contract contract = contractMapper.selectById(contractId);
        if (contract != null && contract.getLateFeeFirst() != null) {
            return contract.getLateFeeFirst();
        }
        return true;
    }

    private BigDecimal totalDue(Bill bill) {
        return nz(bill.getAmount()).subtract(nz(bill.getPaidAmount())).subtract(nz(bill.getReducedAmount()))
                .add(nz(bill.getLateFeeAmount()).subtract(nz(bill.getLateFeePaidAmount())));
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
        BigDecimal principalDue = nz(bill.getAmount()).subtract(nz(bill.getPaidAmount())).subtract(nz(bill.getReducedAmount()));
        BigDecimal lateFeeDue = nz(bill.getLateFeeAmount()).subtract(nz(bill.getLateFeePaidAmount()));
        if (principalDue.compareTo(BigDecimal.ZERO) <= 0 && lateFeeDue.compareTo(BigDecimal.ZERO) <= 0) {
            if (nz(bill.getReducedAmount()).compareTo(BigDecimal.ZERO) > 0
                    && nz(bill.getPaidAmount()).compareTo(BigDecimal.ZERO) <= 0) {
                return BillStatus.REDUCED;
            }
            return BillStatus.PAID;
        }
        if (nz(bill.getPaidAmount()).compareTo(BigDecimal.ZERO) > 0
                || nz(bill.getLateFeePaidAmount()).compareTo(BigDecimal.ZERO) > 0) {
            return BillStatus.PARTIAL_PAID;
        }
        return BillStatus.UNPAID;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
