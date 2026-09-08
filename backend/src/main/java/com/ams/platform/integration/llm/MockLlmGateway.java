package com.ams.platform.integration.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MockLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(MockLlmGateway.class);

    @Override
    public ChatResult chat(List<ChatMessage> messages, Map<String, Object> context) {
        String lastUser = "";
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).role())) {
                    lastUser = messages.get(i).content() == null ? "" : messages.get(i).content();
                    break;
                }
            }
        }
        log.info("LLM mock chat prompt={}", lastUser);
        List<String> tools = new ArrayList<>();
        String lower = lastUser.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "出租", "空置", "收缴", "看板", "经营", "欠费", "面积")) {
            tools.add("dashboard.operations");
        }
        if (containsAny(lower, "催缴", "逾期", "滞纳")) {
            tools.add("dunning.summary");
        }
        if (containsAny(lower, "抵押")) {
            tools.add("mortgage.expiring");
        }
        if (tools.isEmpty()) {
            tools.add("dashboard.operations");
        }
        String answer = "（Mock LLM）已根据问题选择只读工具：" + tools
                + "。下文由系统 Tool 数据填充，指标均可溯源。问题摘要：" + truncate(lastUser, 80);
        return new ChatResult(answer, true, tools);
    }

    @Override
    public boolean isMock() {
        return true;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k.toLowerCase(Locale.ROOT)) || text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
