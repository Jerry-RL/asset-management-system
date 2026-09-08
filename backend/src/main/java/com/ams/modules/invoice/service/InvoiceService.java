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
import com.ams.platform.integration.invoice.DigitalInvoiceAdapter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发票全生命周期（FR-INV-LC-*）：开票 → 数电平台 → 核销勾稽 → 红冲。
 */
@Service
public class InvoiceService {

    private final InvoiceMapper invoiceMapper;
    private final InvoiceTitleMapper titleMapper;
    private final InvoiceReconcileMapper reconcileMapper;
    private final PaymentMapper paymentMapper;
    private final DigitalInvoiceAdapter invoiceAdapter;

    public InvoiceService(
            InvoiceMapper invoiceMapper,
            InvoiceTitleMapper titleMapper,
            InvoiceReconcileMapper reconcileMapper,
            PaymentMapper paymentMapper,
            DigitalInvoiceAdapter invoiceAdapter) {
        this.invoiceMapper = invoiceMapper;
        this.titleMapper = titleMapper;
        this.reconcileMapper = reconcileMapper;
        this.paymentMapper = paymentMapper;
        this.invoiceAdapter = invoiceAdapter;
    }

    /** 开票（FR-INV-LC-001）：收款完成后可开票；对接数电平台（Mock 同步签发）。 */
    @Transactional
    public Invoice issue(Long paymentId, Long titleId, BigDecimal taxRate) {
        Payment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "收款记录不存在");
        }
        if (!"confirmed".equals(payment.getConfirmStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅已确认收款可开票");
        }
        long existing = invoiceMapper.selectCount(
                new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getPaymentId, paymentId)
                        .in(Invoice::getStatus, "pending_issue", "issuing", "issued"));
        if (existing > 0) {
            throw new AppException(ErrorCode.CONFLICT, "该收款已存在有效发票");
        }
        InvoiceTitle title = titleMapper.selectById(titleId);
        if (title == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "发票抬头不存在");
        }
        BigDecimal rate = taxRate == null ? new BigDecimal("0.09") : taxRate;
        BigDecimal taxAmount = payment.getAmount().multiply(rate)
                .divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);

        Invoice invoice = new Invoice();
        invoice.setPaymentId(paymentId);
        invoice.setTitleId(titleId);
        invoice.setAmount(payment.getAmount());
        invoice.setTaxRate(rate);
        invoice.setTaxAmount(taxAmount);
        invoice.setStatus("issuing");
        invoice.setPlatformCode(invoiceAdapter.isMock() ? "mock" : "digital");
        invoiceMapper.insert(invoice);

        DigitalInvoiceAdapter.IssueResult result = invoiceAdapter.issue(
                invoice.getId(), title.getTitle(), title.getTaxNo(),
                invoice.getAmount(), rate, taxAmount);
        if (result.success()) {
            invoice.setStatus("issued");
            invoice.setInvoiceNo(result.invoiceNo());
            invoice.setThirdPartyNo(result.thirdPartyNo());
            invoice.setPdfUrl(result.pdfUrl());
        } else {
            invoice.setStatus("failed");
            invoice.setFailReason(result.failReason());
        }
        invoiceMapper.updateById(invoice);
        return invoice;
    }

    /** 平台回调：更新开票/红冲结果。 */
    @Transactional
    public Invoice handleCallback(String thirdPartyNo, String status, String invoiceNo, String pdfUrl, String failReason) {
        Invoice invoice = invoiceMapper.selectOne(
                new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getThirdPartyNo, thirdPartyNo)
                        .last("LIMIT 1"));
        if (invoice == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "发票不存在");
        }
        if ("issued".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
            invoice.setStatus("issued");
            if (invoiceNo != null) {
                invoice.setInvoiceNo(invoiceNo);
            }
            if (pdfUrl != null) {
                invoice.setPdfUrl(pdfUrl);
            }
        } else if ("red_flushed".equalsIgnoreCase(status) || "red".equalsIgnoreCase(status)) {
            invoice.setStatus("red_flushed");
        } else if ("failed".equalsIgnoreCase(status)) {
            invoice.setStatus("failed");
            invoice.setFailReason(failReason);
        }
        invoiceMapper.updateById(invoice);
        return invoice;
    }

    /** 核销勾稽（FR-INV-LC-002）。 */
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

    /** 红冲（FR-INV-LC-003）。 */
    @Transactional
    public Invoice redFlush(Long invoiceId) {
        Invoice invoice = invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "发票不存在");
        }
        if (!"issued".equals(invoice.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅已开票发票可红冲");
        }
        invoice.setStatus("red_flushing");
        invoiceMapper.updateById(invoice);

        DigitalInvoiceAdapter.RedFlushResult result =
                invoiceAdapter.redFlush(invoiceId, invoice.getThirdPartyNo(), invoice.getAmount());
        if (result.success()) {
            Invoice red = new Invoice();
            red.setPaymentId(invoice.getPaymentId());
            red.setTitleId(invoice.getTitleId());
            red.setAmount(invoice.getAmount().negate());
            red.setTaxRate(invoice.getTaxRate());
            red.setTaxAmount(invoice.getTaxAmount() == null ? null : invoice.getTaxAmount().negate());
            red.setStatus("red_flushed");
            red.setThirdPartyNo(result.thirdPartyNo());
            red.setRedFlushRefId(invoice.getId());
            red.setPlatformCode(invoice.getPlatformCode());
            red.setInvoiceNo("R" + (invoice.getInvoiceNo() == null ? invoice.getId() : invoice.getInvoiceNo()));
            invoiceMapper.insert(red);

            invoice.setStatus("red_flushed");
            invoice.setRedFlushRefId(red.getId());
            invoiceMapper.updateById(invoice);
        } else {
            invoice.setStatus("issued");
            invoice.setFailReason(result.failReason());
            invoiceMapper.updateById(invoice);
            throw new AppException(ErrorCode.INTERNAL_ERROR, "红冲失败: " + result.failReason());
        }
        return invoice;
    }

    /** 按收款自动红冲（退款联动）。 */
    @Transactional
    public void redFlushByPayment(Long paymentId) {
        List<Invoice> list = invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getPaymentId, paymentId)
                        .eq(Invoice::getStatus, "issued"));
        for (Invoice inv : list) {
            redFlush(inv.getId());
        }
    }

    public List<Invoice> list(Long paymentId) {
        return invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>()
                        .eq(paymentId != null, Invoice::getPaymentId, paymentId)
                        .orderByDesc(Invoice::getId));
    }

    public List<InvoiceTitle> listTitles(Long tenantId) {
        return titleMapper.selectList(
                new LambdaQueryWrapper<InvoiceTitle>().eq(InvoiceTitle::getTenantId, tenantId));
    }

    public InvoiceTitle createTitle(InvoiceTitle title) {
        titleMapper.insert(title);
        return title;
    }
}
