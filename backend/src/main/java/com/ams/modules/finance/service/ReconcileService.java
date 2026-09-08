package com.ams.modules.finance.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.finance.entity.BankFlow;
import com.ams.modules.finance.entity.FinanceMonthClose;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.mapper.BankFlowMapper;
import com.ams.modules.finance.mapper.FinanceMonthCloseMapper;
import com.ams.modules.finance.mapper.FinanceVoucherMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业财一体与对账（FR-FIN-*）：
 *  - 银行流水导入与匹配（FR-FIN-001）
 *  - 未达账项（FR-FIN-002）
 *  - 三端收款统一对账（FR-FIN-006）
 *  - 凭证推送（FR-FIN-004，适配器预留）
 *  - 月结锁定（FR-FIN-005）
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    private final BankFlowMapper bankFlowMapper;
    private final PaymentMapper paymentMapper;
    private final FinanceVoucherMapper voucherMapper;
    private final FinanceMonthCloseMapper monthCloseMapper;

    public ReconcileService(
            BankFlowMapper bankFlowMapper,
            PaymentMapper paymentMapper,
            FinanceVoucherMapper voucherMapper,
            FinanceMonthCloseMapper monthCloseMapper) {
        this.bankFlowMapper = bankFlowMapper;
        this.paymentMapper = paymentMapper;
        this.voucherMapper = voucherMapper;
        this.monthCloseMapper = monthCloseMapper;
    }

    /** 导入银行流水并按金额/日期/摘要关键字匹配收款记录。 */
    @Transactional
    public BankFlow importFlow(BankFlow flow) {
        assertPeriodOpen(flow.getTradeDate());
        flow.setMatchStatus("unmatched");
        if (flow.getCreatedAt() == null) {
            flow.setCreatedAt(LocalDateTime.now());
        }
        bankFlowMapper.insert(flow);
        tryAutoMatch(flow);
        return flow;
    }

    /** 批量自动重匹配未达流水。 */
    @Transactional
    public int rematchUnmatched() {
        List<BankFlow> flows = unmatchedFlows();
        int n = 0;
        for (BankFlow flow : flows) {
            if (tryAutoMatch(flow)) {
                n++;
            }
        }
        return n;
    }

    private boolean tryAutoMatch(BankFlow flow) {
        List<Payment> candidates = paymentMapper.selectList(
                new LambdaQueryWrapper<Payment>()
                        .eq(Payment::getConfirmStatus, "confirmed")
                        .eq(Payment::getAmount, flow.getAmount())
                        .and(w -> w.isNull(Payment::getBankReconcileStatus)
                                .or()
                                .ne(Payment::getBankReconcileStatus, "matched")));
        Payment best = null;
        int bestScore = 0;
        for (Payment p : candidates) {
            int score = scoreMatch(flow, p);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best == null || bestScore < 1) {
            return false;
        }
        flow.setMatchStatus("matched");
        flow.setPaymentId(best.getId());
        bankFlowMapper.updateById(flow);
        best.setBankReconcileStatus("matched");
        paymentMapper.updateById(best);
        return true;
    }

    /** 金额必选；同日 +1；摘要命中支付单号/外部单号 +2。 */
    private int scoreMatch(BankFlow flow, Payment payment) {
        int score = 1;
        if (flow.getTradeDate() != null && payment.getPaidAt() != null
                && flow.getTradeDate().equals(payment.getPaidAt().toLocalDate())) {
            score += 1;
        }
        String summary = flow.getSummary() == null ? "" : flow.getSummary();
        if (payment.getPaymentNo() != null && summary.contains(payment.getPaymentNo())) {
            score += 2;
        }
        if (payment.getOutTradeNo() != null && summary.contains(payment.getOutTradeNo())) {
            score += 2;
        }
        return score;
    }

    /** 未达账项：银行流水侧。 */
    public List<BankFlow> unmatchedFlows() {
        return bankFlowMapper.selectList(
                new LambdaQueryWrapper<BankFlow>().eq(BankFlow::getMatchStatus, "unmatched"));
    }

    /** 未达账项：业务收款侧（已确认未勾稽）。 */
    public List<Payment> unmatchedPayments() {
        return paymentMapper.selectList(
                new LambdaQueryWrapper<Payment>()
                        .eq(Payment::getConfirmStatus, "confirmed")
                        .and(w -> w.isNull(Payment::getBankReconcileStatus)
                                .or()
                                .ne(Payment::getBankReconcileStatus, "matched"))
                        .orderByDesc(Payment::getId));
    }

    /** 对账汇总。 */
    public Map<String, Object> reconcileSummary() {
        long unmatchedFlow = unmatchedFlows().size();
        long unmatchedPay = unmatchedPayments().size();
        long matched = bankFlowMapper.selectCount(
                new LambdaQueryWrapper<BankFlow>().eq(BankFlow::getMatchStatus, "matched"));
        Map<String, Object> map = new HashMap<>();
        map.put("unmatchedFlowCount", unmatchedFlow);
        map.put("unmatchedPaymentCount", unmatchedPay);
        map.put("matchedFlowCount", matched);
        return map;
    }

    /** 人工勾稽。 */
    public BankFlow manualMatch(Long flowId, Long paymentId) {
        BankFlow flow = bankFlowMapper.selectById(flowId);
        if (flow == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "流水不存在");
        }
        assertPeriodOpen(flow.getTradeDate());
        Payment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "收款不存在");
        }
        flow.setMatchStatus("matched");
        flow.setPaymentId(paymentId);
        bankFlowMapper.updateById(flow);
        payment.setBankReconcileStatus("matched");
        paymentMapper.updateById(payment);
        return flow;
    }

    /** 生成财务凭证（FR-FIN-004）。 */
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

    /** 推送凭证到财务系统（Mock 日志）。 */
    @Transactional
    public FinanceVoucher pushVoucher(Long voucherId) {
        FinanceVoucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "凭证不存在");
        }
        if ("pushed".equals(voucher.getStatus())) {
            return voucher;
        }
        log.info("ERP voucher push mock voucherNo={} bizType={} bizId={}",
                voucher.getVoucherNo(), voucher.getBizType(), voucher.getBizId());
        voucher.setStatus("pushed");
        voucher.setPushedAt(LocalDateTime.now());
        voucherMapper.updateById(voucher);
        return voucher;
    }

    public List<FinanceVoucher> listVouchers(String status) {
        return voucherMapper.selectList(
                new LambdaQueryWrapper<FinanceVoucher>()
                        .eq(status != null, FinanceVoucher::getStatus, status)
                        .orderByDesc(FinanceVoucher::getId));
    }

    public FinanceMonthClose getOrCreateMonth(String period) {
        YearMonth ym = YearMonth.parse(period);
        String key = ym.toString();
        FinanceMonthClose existing = monthCloseMapper.selectOne(
                new LambdaQueryWrapper<FinanceMonthClose>().eq(FinanceMonthClose::getPeriod, key));
        if (existing != null) {
            return existing;
        }
        FinanceMonthClose close = new FinanceMonthClose();
        close.setPeriod(key);
        close.setStatus("open");
        close.setCreatedAt(LocalDateTime.now());
        monthCloseMapper.insert(close);
        return close;
    }

    @Transactional
    public FinanceMonthClose lockMonth(String period, String remark) {
        FinanceMonthClose close = getOrCreateMonth(period);
        if ("locked".equals(close.getStatus())) {
            return close;
        }
        close.setStatus("locked");
        close.setLockedAt(LocalDateTime.now());
        close.setLockedBy(SecurityUtils.currentUserIdOrNull());
        close.setRemark(remark);
        monthCloseMapper.updateById(close);
        return close;
    }

    @Transactional
    public FinanceMonthClose unlockMonth(String period, String remark) {
        FinanceMonthClose close = getOrCreateMonth(period);
        close.setStatus("open");
        close.setLockedAt(null);
        close.setLockedBy(null);
        close.setRemark(remark);
        monthCloseMapper.updateById(close);
        return close;
    }

    public List<FinanceMonthClose> listMonthCloses() {
        return monthCloseMapper.selectList(
                new LambdaQueryWrapper<FinanceMonthClose>().orderByDesc(FinanceMonthClose::getPeriod));
    }

    private void assertPeriodOpen(LocalDate tradeDate) {
        if (tradeDate == null) {
            return;
        }
        String period = YearMonth.from(tradeDate).toString();
        FinanceMonthClose close = monthCloseMapper.selectOne(
                new LambdaQueryWrapper<FinanceMonthClose>().eq(FinanceMonthClose::getPeriod, period));
        if (close != null && "locked".equals(close.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "会计期间 " + period + " 已月结锁定");
        }
    }
}
