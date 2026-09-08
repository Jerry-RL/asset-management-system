package com.ams.modules.disposal.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.service.PaymentService;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.platform.approval.ApprovalEngine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产处置全生命周期闭环（FR-DISP-*，§4.24.5 / FR-DISP-006）。
 * 流程：处置申请 → 审批 → 执行 → 损益入账/凭证 → 已退出。
 */
@Service
public class DisposalService {

    private final DisposalOrderMapper disposalOrderMapper;
    private final LeaseControlService leaseControlService;
    private final ApprovalEngine approvalEngine;
    private final CertificateService certificateService;
    private final PaymentService paymentService;
    private final ReconcileService reconcileService;
    private final ObjectMapper objectMapper;

    public DisposalService(
            DisposalOrderMapper disposalOrderMapper,
            LeaseControlService leaseControlService,
            ApprovalEngine approvalEngine,
            CertificateService certificateService,
            PaymentService paymentService,
            ReconcileService reconcileService,
            ObjectMapper objectMapper) {
        this.disposalOrderMapper = disposalOrderMapper;
        this.leaseControlService = leaseControlService;
        this.approvalEngine = approvalEngine;
        this.certificateService = certificateService;
        this.paymentService = paymentService;
        this.reconcileService = reconcileService;
        this.objectMapper = objectMapper;
    }

    public PageResult<DisposalOrder> page(long page, long pageSize, String status) {
        Page<DisposalOrder> result = disposalOrderMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<DisposalOrder>()
                        .eq(status != null, DisposalOrder::getStatus, status)
                        .orderByDesc(DisposalOrder::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    /** 处置申请（FR-DISP-001）。 */
    public DisposalOrder create(DisposalOrder order) {
        certificateService.assertNotMortgaged(order.getAssetId());
        order.setStatus("draft");
        disposalOrderMapper.insert(order);
        return order;
    }

    /** 提交审批（FR-DISP-002）。 */
    @Transactional
    public DisposalOrder submit(Long id) {
        DisposalOrder order = require(id);
        certificateService.assertNotMortgaged(order.getAssetId());
        order.setStatus("approving");
        disposalOrderMapper.updateById(order);
        BigDecimal value = order.getAssessedValue() != null ? order.getAssessedValue() : order.getBookValue();
        String bizType = value != null && value.compareTo(new BigDecimal("1000000")) >= 0
                ? "disposal_major"
                : "disposal";
        approvalEngine.start(bizType, id);
        return order;
    }

    /** 审批通过 → 待执行 + 租控处置中。 */
    @Transactional
    public void onApproved(Long id) {
        DisposalOrder order = require(id);
        order.setStatus("pending_execute");
        disposalOrderMapper.updateById(order);
        leaseControlService.transition(order.getAssetId(), LeaseControlStatus.DISPOSING,
                "disposal", id, "处置审批通过");
    }

    /** 执行登记（FR-DISP-003）。 */
    @Transactional
    public DisposalOrder execute(Long id, BigDecimal actualAmount, String counterparty) {
        DisposalOrder order = require(id);
        order.setStatus("executing");
        order.setActualAmount(actualAmount);
        order.setCounterparty(counterparty);
        disposalOrderMapper.updateById(order);
        return order;
    }

    /**
     * 完成 → 损益入账 + 收款 + 凭证 → 已退出（FR-DISP-004/005/006）。
     * pnl = actual − max(book, assessed)；收入入收款记录并生成待推送凭证。
     */
    @Transactional
    public DisposalOrder complete(Long id) {
        DisposalOrder order = require(id);
        if (!"executing".equals(order.getStatus()) && !"pending_execute".equals(order.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可完成处置");
        }
        BigDecimal actual = order.getActualAmount() == null ? BigDecimal.ZERO : order.getActualAmount();
        BigDecimal book = order.getBookValue();
        BigDecimal assessed = order.getAssessedValue();
        BigDecimal basis = book;
        if (basis == null) {
            basis = assessed;
        } else if (assessed != null) {
            basis = book.max(assessed);
        }
        if (basis == null) {
            basis = BigDecimal.ZERO;
        }
        BigDecimal pnl = actual.subtract(basis);
        order.setPnlAmount(pnl);
        order.setPnlType(pnl.compareTo(BigDecimal.ZERO) >= 0 ? "gain" : "loss");

        if (actual.compareTo(BigDecimal.ZERO) > 0) {
            Payment payment = paymentService.registerConfirmed(
                    null, null, actual, "bank_transfer", "pc");
            payment.setRemark("disposal:" + order.getId()
                    + (order.getCounterparty() == null ? "" : ":" + order.getCounterparty()));
            payment.setSource("disposal");
            paymentService.update(payment);
            order.setPaymentId(payment.getId());
        }

        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("disposalId", order.getId());
            content.put("assetId", order.getAssetId());
            content.put("disposalType", order.getDisposalType());
            content.put("actualAmount", actual);
            content.put("bookValue", book);
            content.put("assessedValue", assessed);
            content.put("pnlAmount", pnl);
            content.put("pnlType", order.getPnlType());
            content.put("paymentId", order.getPaymentId());
            FinanceVoucher voucher = reconcileService.createVoucher(
                    "disposal", order.getId(), objectMapper.writeValueAsString(content));
            order.setVoucherId(voucher.getId());
        } catch (Exception ex) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "生成处置凭证失败: " + ex.getMessage());
        }

        order.setStatus("completed");
        disposalOrderMapper.updateById(order);
        leaseControlService.transition(order.getAssetId(), LeaseControlStatus.EXITED,
                "disposal", id, "处置完成，资产已退出");
        return order;
    }

    private DisposalOrder require(Long id) {
        DisposalOrder order = disposalOrderMapper.selectById(id);
        if (order == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return order;
    }
}
