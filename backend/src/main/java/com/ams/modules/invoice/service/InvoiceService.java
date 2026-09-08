package com.ams.modules.invoice.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.mapper.PaymentMapper;
import com.ams.modules.invoice.entity.Invoice;
import com.ams.modules.invoice.entity.InvoiceReconcile;
import com.ams.modules.invoice.entity.InvoiceTitle;
import com.ams.modules.invoice.mapper.InvoiceMapper;
import com.ams.modules.invoice.mapper.InvoiceReconcileMapper;
import com.ams.modules.invoice.mapper.InvoiceTitleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发票全生命周期（FR-INV-LC-*，§4.24.8）：开票触发 → 核销勾稽 → 红冲 → 归档。
 * 第三方开票走异步适配器（生产对接数电发票平台），此处实现业务状态机与勾稽。
 */
@Service
public class InvoiceService {

    private final InvoiceMapper invoiceMapper;
    private final InvoiceTitleMapper titleMapper;
    private final InvoiceReconcileMapper reconcileMapper;
    private final PaymentMapper paymentMapper;

    public InvoiceService(
            InvoiceMapper invoiceMapper,
            InvoiceTitleMapper titleMapper,
            InvoiceReconcileMapper reconcileMapper,
            PaymentMapper paymentMapper) {
        this.invoiceMapper = invoiceMapper;
        this.titleMapper = titleMapper;
        this.reconcileMapper = reconcileMapper;
        this.paymentMapper = paymentMapper;
    }

    /** 开票（FR-INV-LC-001）：收款完成后可开票。 */
    @Transactional
    public Invoice issue(Long paymentId, Long titleId, BigDecimal taxRate) {
        Payment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "收款记录不存在");
        }
        InvoiceTitle title = titleMapper.selectById(titleId);
        if (title == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "发票抬头不存在");
        }
        BigDecimal rate = taxRate == null ? new BigDecimal("0.09") : taxRate;
        BigDecimal taxAmount = payment.getAmount().multiply(rate)
                .divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);

        Invoice invoice = new Invoice();
        invoice.setInvoiceNo("FP" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        invoice.setPaymentId(paymentId);
        invoice.setTitleId(titleId);
        invoice.setAmount(payment.getAmount());
        invoice.setTaxRate(rate);
        invoice.setTaxAmount(taxAmount);
        invoice.setStatus("issued"); // 生产走异步：pending_issue → issuing → issued
        invoiceMapper.insert(invoice);
        return invoice;
    }

    /** 核销勾稽（FR-INV-LC-002）：发票 ↔ 收款 ↔ 账单三方勾稽。 */
    @Transactional
    public InvoiceReconcile reconcile(Long invoiceId, Long paymentId, Long billId, BigDecimal amount) {
        InvoiceReconcile rec = new InvoiceReconcile();
        rec.setInvoiceId(invoiceId);
        rec.setPaymentId(paymentId);
        rec.setBillId(billId);
        rec.setAmount(amount);
        rec.setCreatedAt(LocalDateTime.now());
        reconcileMapper.insert(rec);
        return rec;
    }

    /** 红冲（FR-INV-LC-003）：退款/冲正触发红冲申请。 */
    @Transactional
    public Invoice redFlush(Long invoiceId) {
        Invoice invoice = invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "发票不存在");
        }
        if (!"issued".equals(invoice.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅已开票发票可红冲");
        }
        invoice.setStatus("red_flushed");
        invoiceMapper.updateById(invoice);
        return invoice;
    }

    public List<Invoice> list(Long paymentId) {
        return invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>()
                        .eq(paymentId != null, Invoice::getPaymentId, paymentId)
                        .orderByDesc(Invoice::getId));
    }

    // ---- 抬头 ----
    public List<InvoiceTitle> listTitles(Long tenantId) {
        return titleMapper.selectList(
                new LambdaQueryWrapper<InvoiceTitle>().eq(InvoiceTitle::getTenantId, tenantId));
    }

    public InvoiceTitle createTitle(InvoiceTitle title) {
        titleMapper.insert(title);
        return title;
    }
}
