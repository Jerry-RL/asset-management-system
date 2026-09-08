package com.ams.modules.adjustment.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.adjustment.entity.FeeReliefRequest;
import com.ams.modules.adjustment.mapper.FeeReliefRequestMapper;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.compliance.service.ComplianceService;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 费用减免（FR-ADJ-001~003 / FR-ADJ-005 / FR-COMP-002）。
 */
@Service
public class FeeReliefService {

    /** 大额阈值：绝对值或占比。 */
    public static final BigDecimal MAJOR_AMOUNT = new BigDecimal("10000");
    public static final BigDecimal MAJOR_RATIO = new BigDecimal("0.30");

    private final FeeReliefRequestMapper requestMapper;
    private final BillMapper billMapper;
    private final ApprovalEngine approvalEngine;
    private final ReconcileService reconcileService;
    private final ComplianceService complianceService;
    private final ObjectMapper objectMapper;

    public FeeReliefService(
            FeeReliefRequestMapper requestMapper,
            BillMapper billMapper,
            ApprovalEngine approvalEngine,
            ReconcileService reconcileService,
            ComplianceService complianceService,
            ObjectMapper objectMapper) {
        this.requestMapper = requestMapper;
        this.billMapper = billMapper;
        this.approvalEngine = approvalEngine;
        this.reconcileService = reconcileService;
        this.complianceService = complianceService;
        this.objectMapper = objectMapper;
    }

    public List<FeeReliefRequest> list(Long billId, Long contractId) {
        return requestMapper.selectList(
                new LambdaQueryWrapper<FeeReliefRequest>()
                        .eq(billId != null, FeeReliefRequest::getBillId, billId)
                        .eq(contractId != null, FeeReliefRequest::getContractId, contractId)
                        .orderByDesc(FeeReliefRequest::getId));
    }

    public FeeReliefRequest get(Long id) {
        FeeReliefRequest req = requestMapper.selectById(id);
        if (req == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "减免申请不存在");
        }
        return req;
    }

    @Transactional
    public FeeReliefRequest apply(Long billId, BigDecimal reliefAmount, String reason, String fileIds) {
        Bill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "账单不存在");
        }
        if (!BillStatus.UNPAID.equals(bill.getStatus()) && !BillStatus.PARTIAL_PAID.equals(bill.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅待缴/部分缴账单可申请减免");
        }
        long pending = requestMapper.selectCount(
                new LambdaQueryWrapper<FeeReliefRequest>()
                        .eq(FeeReliefRequest::getBillId, billId)
                        .in(FeeReliefRequest::getStatus, "draft", "approving", "approved", "applied"));
        if (pending > 0) {
            throw new AppException(ErrorCode.CONFLICT, "该账单已有减免申请，不可重复减免");
        }
        BigDecimal outstanding = outstanding(bill);
        if (reliefAmount == null || reliefAmount.compareTo(BigDecimal.ZERO) <= 0
                || reliefAmount.compareTo(outstanding) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "减免金额须大于0且不超过未缴金额");
        }

        boolean major = isMajor(reliefAmount, outstanding);
        FeeReliefRequest req = new FeeReliefRequest();
        req.setBillId(billId);
        req.setContractId(bill.getContractId());
        req.setTenantId(bill.getTenantId());
        req.setReliefAmount(reliefAmount);
        req.setReason(reason);
        req.setFileIds(fileIds);
        req.setMajorFlag(major);
        req.setStatus("draft");
        req.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        req.setCreatedAt(LocalDateTime.now());
        requestMapper.insert(req);
        return req;
    }

    @Transactional
    public ApprovalInstance submit(Long requestId) {
        FeeReliefRequest req = get(requestId);
        if (!"draft".equals(req.getStatus()) && !"rejected".equals(req.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可提交审批");
        }
        Bill bill = billMapper.selectById(req.getBillId());
        String bizType = Boolean.TRUE.equals(req.getMajorFlag()) ? "rent_relief_major" : "fee_relief";
        req.setApprovalBizType(bizType);
        req.setStatus("approving");
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(req);
        if (Boolean.TRUE.equals(req.getMajorFlag())) {
            complianceService.build("rent_relief_major", req.getId(), true);
        }
        return approvalEngine.start(bizType, req.getId());
    }

    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (!"fee_relief".equals(event.getBizType()) && !"rent_relief_major".equals(event.getBizType())) {
            return;
        }
        FeeReliefRequest req = requestMapper.selectById(event.getBizId());
        if (req == null || !"approving".equals(req.getStatus())) {
            return;
        }
        if (event.isApproved()) {
            applyRelief(req);
        } else {
            req.setStatus("rejected");
            req.setUpdatedAt(LocalDateTime.now());
            requestMapper.updateById(req);
        }
    }

    private void applyRelief(FeeReliefRequest req) {
        Bill bill = billMapper.selectById(req.getBillId());
        if (bill == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "账单不存在");
        }
        BigDecimal already = bill.getReducedAmount() == null ? BigDecimal.ZERO : bill.getReducedAmount();
        bill.setReducedAmount(already.add(req.getReliefAmount()));
        BigDecimal unpaid = outstanding(bill);
        if (unpaid.compareTo(BigDecimal.ZERO) <= 0) {
            bill.setStatus(BillStatus.REDUCED);
        }
        bill.setRemark(append(bill.getRemark(), "减免 " + req.getReliefAmount() + " 申请#" + req.getId()));
        billMapper.updateById(bill);

        req.setStatus("applied");
        req.setAppliedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(req);

        pushVoucher(req, bill);
    }

    private void pushVoucher(FeeReliefRequest req, Bill bill) {
        try {
            Map<String, Object> content = new HashMap<>();
            content.put("type", "fee_relief");
            content.put("requestId", req.getId());
            content.put("billId", bill.getId());
            content.put("billNo", bill.getBillNo());
            content.put("amount", req.getReliefAmount());
            content.put("major", req.getMajorFlag());
            reconcileService.createVoucher("fee_relief", req.getId(), objectMapper.writeValueAsString(content));
        } catch (Exception ignored) {
            // 凭证失败不阻断减免生效
        }
    }

    public static boolean isMajor(BigDecimal relief, BigDecimal outstanding) {
        if (relief.compareTo(MAJOR_AMOUNT) >= 0) {
            return true;
        }
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal ratio = relief.divide(outstanding, 4, RoundingMode.HALF_UP);
        return ratio.compareTo(MAJOR_RATIO) >= 0;
    }

    private static BigDecimal outstanding(Bill bill) {
        BigDecimal amount = bill.getAmount() == null ? BigDecimal.ZERO : bill.getAmount();
        BigDecimal paid = bill.getPaidAmount() == null ? BigDecimal.ZERO : bill.getPaidAmount();
        BigDecimal reduced = bill.getReducedAmount() == null ? BigDecimal.ZERO : bill.getReducedAmount();
        return amount.subtract(paid).subtract(reduced).max(BigDecimal.ZERO);
    }

    private static String append(String base, String more) {
        if (base == null || base.isBlank()) {
            return more;
        }
        return base + "; " + more;
    }
}
