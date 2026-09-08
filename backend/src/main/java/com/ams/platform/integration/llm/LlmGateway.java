package com.ams.platform.integration.llm;

import java.util.List;
import java.util.Map;

/**
 * 私有化 LLM 网关（NFR-AI-*）。未配置时 Mock 降级。
 */
public interface LlmGateway {

    record ChatMessage(String role, String content) {
    }

    record ChatResult(String content, boolean mock, List<String> suggestedTools) {
    }

    ChatResult chat(List<ChatMessage> messages, Map<String, Object> context);

    boolean isMock();
}
