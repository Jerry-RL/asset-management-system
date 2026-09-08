package com.ams.platform.integration.esign;

import java.util.Map;
import java.util.UUID;

/**
 * 电子签适配器（FR-ESIGN）。Mock 模式可直接完成签署。
 */
public interface ESignAdapter {

    record SignSession(String flowId, String signUrl, boolean mock) {
    }

    SignSession createSignFlow(Long contractId, String contractNo, String signerName, String signerPhone);

    boolean verifyCallback(Map<String, String> headers, String body);

    default String mockFlowId() {
        return "ES" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
