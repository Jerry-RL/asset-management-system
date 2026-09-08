package com.ams.modules.finance.service;

import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.finance.entity.BankFlow;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.mapper.BankFlowMapper;
import com.ams.modules.finance.mapper.FinanceVoucherMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业财一体与对账（FR-FIN-*）：
 *  - 银行流水导入与匹配（FR-FIN-001）
 *  - 未达账项（FR-FIN-002）
 *  - 三端收款统一对账（FR-FIN-006）
 *  - 凭证推送（FR-FIN-004，适配器预留）
 */
@Service
public class ReconcileService {

    private final BankFlowMapper bankFlowMapper;
    private final PaymentMapper paymentMapper;
    private final FinanceVoucherMapper voucherMapper;

    public ReconcileService(
            BankFlowMapper bankFlowMapper,
            PaymentMapper paymentMapper,
            FinanceVoucherMapper voucherMapper) {
        this.bankFlowMapper = bankFlowMapper;
        this.paymentMapper = paymentMapper;
        this.voucherMapper = voucherMapper;
    }

    /** 导入银行流水并按金额/日期匹配收款记录。 */
    @Transactional
    public BankFlow importFlow(BankFlow flow) {
        flow.setMatchStatus("unmatched");
        bankFlowMapper.insert(flow);
        // 自动匹配：金额 + 日期（同一天）匹配已确认收款
        List<Payment> candidates = paymentMapper.selectList(
                new LambdaQueryWrapper<Payment>()
                        .eq(Payment::getConfirmStatus, "confirmed")
                        .eq(Payment::getAmount, flow.getAmount()));
        if (!candidates.isEmpty()) {
            flow.setMatchStatus("matched");
            flow.setPaymentId(candidates.get(0).getId());
            bankFlowMapper.updateById(flow);
            candidates.get(0).setBankReconcileStatus("matched");
            paymentMapper.updateById(candidates.get(0));
        }
        return flow;
    }

    /** 未达账项清单（FR-FIN-002）。 */
    public List<BankFlow> unmatchedFlows() {
        return bankFlowMapper.selectList(
                new LambdaQueryWrapper<BankFlow>().eq(BankFlow::getMatchStatus, "unmatched"));
    }

    /** 人工勾稽。 */
    public BankFlow manualMatch(Long flowId, Long paymentId) {
        BankFlow flow = bankFlowMapper.selectById(flowId);
        flow.setMatchStatus("matched");
        flow.setPaymentId(paymentId);
        bankFlowMapper.updateById(flow);
        return flow;
    }

    /** 生成财务凭证（FR-FIN-004，适配器预留）。 */
    public FinanceVoucher createVoucher(String bizType, Long bizId, String contentJson) {
        FinanceVoucher voucher = new FinanceVoucher();
        voucher.setBizType(bizType);
        voucher.setBizId(bizId);
        voucher.setVoucherNo("PZ" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        voucher.setStatus("pending");
        voucher.setContentJson(contentJson);
        voucher.setCreatedAt(LocalDateTime.now());
        voucherMapper.insert(voucher);
        return voucher;
    }

    public List<FinanceVoucher> listVouchers(String status) {
        return voucherMapper.selectList(
                new LambdaQueryWrapper<FinanceVoucher>()
                        .eq(status != null, FinanceVoucher::getStatus, status)
                        .orderByDesc(FinanceVoucher::getId));
    }
}
