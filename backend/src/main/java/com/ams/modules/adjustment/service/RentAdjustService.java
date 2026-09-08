package com.ams.modules.adjustment.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.adjustment.entity.RentAdjustRequest;
import com.ams.modules.adjustment.mapper.RentAdjustRequestMapper;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.contract.service.ContractVersionService;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.modules.pricing.PlanGenerator;
import com.ams.modules.pricing.entity.PaymentPlan;
import com.ams.modules.pricing.mapper.PaymentPlanMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租金调价（FR-ADJ-004 / FR-ADJ-005）：审批通过后更新合同租金、重算未出账计划；已出账可补差账单。
 */
@Service
public class RentAdjustService {

    private final RentAdjustRequestMapper requestMapper;
    private final ContractMapper contractMapper;
    private final PlanGenerator planGenerator;
    private final PaymentPlanMapper paymentPlanMapper;
    private final BillMapper billMapper;
    private final ApprovalEngine approvalEngine;
    private final ContractVersionService contractVersionService;
    private final ReconcileService reconcileService;
    private final ObjectMapper objectMapper;

    public RentAdjustService(
            RentAdjustRequestMapper requestMapper,
            ContractMapper contractMapper,
            PlanGenerator planGenerator,
            PaymentPlanMapper paymentPlanMapper,
            BillMapper billMapper,
            ApprovalEngine approvalEngine,
            ContractVersionService contractVersionService,
            ReconcileService reconcileService,
            ObjectMapper objectMapper) {
        this.requestMapper = requestMapper;
        this.contractMapper = contractMapper;
        this.planGenerator = planGenerator;
        this.paymentPlanMapper = paymentPlanMapper;
        this.billMapper = billMapper;
        this.approvalEngine = approvalEngine;
        this.contractVersionService = contractVersionService;
        this.reconcileService = reconcileService;
        this.objectMapper = objectMapper;
    }

    public List<RentAdjustRequest> list(Long contractId) {
        return requestMapper.selectList(
                new LambdaQueryWrapper<RentAdjustRequest>()
                        .eq(contractId != null, RentAdjustRequest::getContractId, contractId)
                        .orderByDesc(RentAdjustRequest::getId));
    }

    public RentAdjustRequest get(Long id) {
        RentAdjustRequest req = requestMapper.selectById(id);
        if (req == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "调价申请不存在");
        }
        return req;
    }

    @Transactional
    public RentAdjustRequest apply(
            Long contractId,
            BigDecimal newRentAmount,
            LocalDate effectiveDate,
            String issuedStrategy,
            String reason,
            String fileIds) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "合同不存在");
        }
        if (!ContractStatus.ACTIVE.equals(contract.getStatus())
                && !ContractStatus.EXPIRING.equals(contract.getStatus())
                && !ContractStatus.RENEWABLE.equals(contract.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅在租/临期合同可调价");
        }
        if (newRentAmount == null || newRentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "新租金须大于0");
        }
        long pending = requestMapper.selectCount(
                new LambdaQueryWrapper<RentAdjustRequest>()
                        .eq(RentAdjustRequest::getContractId, contractId)
                        .in(RentAdjustRequest::getStatus, "draft", "approving"));
        if (pending > 0) {
            throw new AppException(ErrorCode.CONFLICT, "已有进行中的调价申请");
        }

        RentAdjustRequest req = new RentAdjustRequest();
        req.setContractId(contractId);
        req.setOldRentAmount(contract.getRentAmount());
        req.setNewRentAmount(newRentAmount);
        req.setEffectiveDate(effectiveDate == null ? LocalDate.now() : effectiveDate);
        req.setIssuedStrategy(issuedStrategy == null || issuedStrategy.isBlank() ? "keep" : issuedStrategy);
        req.setReason(reason);
        req.setFileIds(fileIds);
        req.setStatus("draft");
        req.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        req.setCreatedAt(LocalDateTime.now());
        requestMapper.insert(req);
        return req;
    }

    @Transactional
    public ApprovalInstance submit(Long requestId) {
        RentAdjustRequest req = get(requestId);
        if (!"draft".equals(req.getStatus()) && !"rejected".equals(req.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可提交审批");
        }
        req.setStatus("approving");
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(req);
        return approvalEngine.start("rent_adjust", req.getId());
    }

    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (!"rent_adjust".equals(event.getBizType())) {
            return;
        }
        RentAdjustRequest req = requestMapper.selectById(event.getBizId());
        if (req == null || !"approving".equals(req.getStatus())) {
            return;
        }
        if (event.isApproved()) {
            applyAdjust(req);
        } else {
            req.setStatus("rejected");
            req.setUpdatedAt(LocalDateTime.now());
            requestMapper.updateById(req);
        }
    }

    private void applyAdjust(RentAdjustRequest req) {
        Contract contract = contractMapper.selectById(req.getContractId());
        if (contract == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "合同不存在");
        }
        Contract before = new Contract();
        org.springframework.beans.BeanUtils.copyProperties(contract, before);

        contract.setRentAmount(req.getNewRentAmount());
        contract.setRemark(append(contract.getRemark(),
                "调价 " + req.getOldRentAmount() + "→" + req.getNewRentAmount() + " #" + req.getId()));
        contractMapper.updateById(contract);
        contractVersionService.snapshot(before, contract, "rent_adjust");

        planGenerator.regeneratePending(contract.getId());

        if ("diff_bill".equals(req.getIssuedStrategy())) {
            createDiffBills(req, contract);
        }

        req.setStatus("applied");
        req.setAppliedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(req);
        pushVoucher(req);
    }

    private void createDiffBills(RentAdjustRequest req, Contract contract) {
        BigDecimal oldAmt = req.getOldRentAmount() == null ? BigDecimal.ZERO : req.getOldRentAmount();
        BigDecimal diffUnit = req.getNewRentAmount().subtract(oldAmt);
        if (diffUnit.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        List<PaymentPlan> issued = paymentPlanMapper.selectList(
                new LambdaQueryWrapper<PaymentPlan>()
                        .eq(PaymentPlan::getContractId, contract.getId())
                        .eq(PaymentPlan::getStatus, "issued")
                        .ge(PaymentPlan::getDueDate, req.getEffectiveDate()));
        for (PaymentPlan plan : issued) {
            Bill bill = new Bill();
            bill.setBillNo("ADJ" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            bill.setContractId(contract.getId());
            bill.setPlanId(plan.getId());
            bill.setAssetId(contract.getAssetId());
            bill.setTenantId(contract.getTenantId());
            bill.setBillType("rent_adjust");
            bill.setPeriodStart(plan.getPeriodStart());
            bill.setPeriodEnd(plan.getPeriodEnd());
            bill.setDueDate(LocalDate.now().plusDays(7));
            // 按周期计划金额比例估算差额：差额 = 计划额 * (新-旧)/旧
            BigDecimal diff;
            if (oldAmt.compareTo(BigDecimal.ZERO) > 0 && plan.getPlannedAmount() != null) {
                diff = plan.getPlannedAmount()
                        .multiply(diffUnit)
                        .divide(oldAmt, 2, RoundingMode.HALF_UP);
            } else {
                diff = diffUnit.setScale(2, RoundingMode.HALF_UP);
            }
            if (diff.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            bill.setAmount(diff);
            bill.setPaidAmount(BigDecimal.ZERO);
            bill.setReducedAmount(BigDecimal.ZERO);
            bill.setLateFeeAmount(BigDecimal.ZERO);
            bill.setLateFeePaidAmount(BigDecimal.ZERO);
            bill.setStatus(diff.compareTo(BigDecimal.ZERO) > 0 ? BillStatus.UNPAID : BillStatus.UNPAID);
            bill.setSource("rent_adjust");
            bill.setRemark("调价补差申请#" + req.getId());
            billMapper.insert(bill);
        }
    }

    private void pushVoucher(RentAdjustRequest req) {
        try {
            Map<String, Object> content = new HashMap<>();
            content.put("type", "rent_adjust");
            content.put("requestId", req.getId());
            content.put("contractId", req.getContractId());
            content.put("oldRent", req.getOldRentAmount());
            content.put("newRent", req.getNewRentAmount());
            content.put("strategy", req.getIssuedStrategy());
            reconcileService.createVoucher("rent_adjust", req.getId(), objectMapper.writeValueAsString(content));
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static String append(String base, String more) {
        if (base == null || base.isBlank()) {
            return more;
        }
        return base + "; " + more;
    }
}
