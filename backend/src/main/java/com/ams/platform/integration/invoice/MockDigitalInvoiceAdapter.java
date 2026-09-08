package com.ams.platform.integration.invoice;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MockDigitalInvoiceAdapter implements DigitalInvoiceAdapter {

    private static final Logger log = LoggerFactory.getLogger(MockDigitalInvoiceAdapter.class);

    @Override
    public IssueResult issue(
            Long invoiceId,
            String buyerTitle,
            String taxNo,
            BigDecimal amount,
            BigDecimal taxRate,
            BigDecimal taxAmount) {
        String tp = "MOCK_INV_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String no = "SD" + System.currentTimeMillis();
        log.info("Digital invoice mock issue invoiceId={} title={} amount={} tp={}",
                invoiceId, buyerTitle, amount, tp);
        return new IssueResult(true, tp, no, "/mock/invoice/" + tp + ".pdf", null);
    }

    @Override
    public RedFlushResult redFlush(Long invoiceId, String originalThirdPartyNo, BigDecimal amount) {
        String tp = "MOCK_RED_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        log.info("Digital invoice mock red-flush invoiceId={} original={} amount={}",
                invoiceId, originalThirdPartyNo, amount);
        return new RedFlushResult(true, tp, null);
    }

    @Override
    public boolean verifyCallback(Map<String, String> headers, String body) {
        return true;
    }

    @Override
    public boolean isMock() {
        return true;
    }
}
