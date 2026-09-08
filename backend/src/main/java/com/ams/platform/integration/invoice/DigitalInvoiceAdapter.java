package com.ams.platform.integration.invoice;

import java.math.BigDecimal;

/**
 * 数电发票平台适配（FR-INV-LC）。未配置时走 Mock。
 */
public interface DigitalInvoiceAdapter {

    record IssueResult(boolean success, String thirdPartyNo, String invoiceNo, String pdfUrl, String failReason) {
    }

    record RedFlushResult(boolean success, String thirdPartyNo, String failReason) {
    }

    IssueResult issue(
            Long invoiceId,
            String buyerTitle,
            String taxNo,
            BigDecimal amount,
            BigDecimal taxRate,
            BigDecimal taxAmount);

    RedFlushResult redFlush(Long invoiceId, String originalThirdPartyNo, BigDecimal amount);

    boolean verifyCallback(java.util.Map<String, String> headers, String body);

    boolean isMock();
}
