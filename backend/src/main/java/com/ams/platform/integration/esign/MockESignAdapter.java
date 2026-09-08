package com.ams.platform.integration.esign;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MockESignAdapter implements ESignAdapter {

    private static final Logger log = LoggerFactory.getLogger(MockESignAdapter.class);

    @Override
    public SignSession createSignFlow(Long contractId, String contractNo, String signerName, String signerPhone) {
        String flowId = mockFlowId();
        log.info("ESign mock create flowId={} contractId={} signer={}", flowId, contractId, signerName);
        return new SignSession(flowId, "/esign/mock/" + flowId, true);
    }

    @Override
    public boolean verifyCallback(Map<String, String> headers, String body) {
        return true;
    }
}
