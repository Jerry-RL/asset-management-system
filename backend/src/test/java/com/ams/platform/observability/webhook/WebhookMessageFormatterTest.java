package com.ams.platform.observability.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 三家 Webhook 报文格式测试（设计 §10.2）。
 *
 * <p>这里断言的是<strong>报文结构</strong>而不是「拼了一个字符串」：企微与钉钉的机器人
 * 对 {@code msgtype} 与 markdown 字段名的要求不同，拼错了对方会返回 200 但消息不出现 ——
 * 那种失败在现场极难定位，因此必须由测试锁住。
 */
class WebhookMessageFormatterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookMessageFormatter formatter = new WebhookMessageFormatter(objectMapper);

    private WebhookPayload payload() {
        return new WebhookPayload(
                "ERROR",
                "TypeError: x is not a function\nat a.js:1:1",
                "trace-1",
                "h5-tenant",
                "js",
                "https://h5.example/bills",
                "fp-1",
                12,
                LocalDateTime.of(2026, 9, 12, 20, 0));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new AssertionError("报文不是合法 JSON：" + json, ex);
        }
    }

    @Test
    @DisplayName("wecom：msgtype=markdown，正文放 markdown.content")
    void rendersWecom() {
        String json = formatter.format("wecom", "标题", "### 标题\n正文", payload());
        JsonNode root = parse(json);

        assertThat(root.path("msgtype").asText()).isEqualTo("markdown");
        assertThat(root.path("markdown").path("content").asText()).contains("### 标题");
    }

    @Test
    @DisplayName("dingtalk：msgtype=markdown，title 与 text 分离")
    void rendersDingtalk() {
        String json = formatter.format("dingtalk", "标题", "### 标题\n正文", payload());
        JsonNode root = parse(json);

        assertThat(root.path("msgtype").asText()).isEqualTo("markdown");
        assertThat(root.path("markdown").path("title").asText()).isEqualTo("标题");
        assertThat(root.path("markdown").path("text").asText()).contains("### 标题");
    }

    @Test
    @DisplayName("generic：结构化字段，便于订阅方程序化处理")
    void rendersGeneric() {
        String json = formatter.format("generic", "标题", "正文", payload());
        JsonNode root = parse(json);

        assertThat(root.path("event").asText()).isEqualTo("app_log_alert");
        assertThat(root.path("fingerprint").asText()).isEqualTo("fp-1");
        assertThat(root.path("count").asInt()).isEqualTo(12);
        assertThat(root.path("traceId").asText()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("无法识别的类型/大小写/空值 -> 回退 generic，绝不因为配错就不发告警")
    void fallsBackToGeneric() {
        for (String type : new String[] {null, "", "  ", "slack", "WECOM_typo"}) {
            String json = formatter.format(type, "标题", "正文", payload());
            assertThat(parse(json).path("event").asText())
                    .as("type=%s 应回退 generic", type)
                    .isEqualTo("app_log_alert");
        }
    }

    @Test
    @DisplayName("类型大小写不敏感（运维填 WeCom 也要能用）")
    void typeIsCaseInsensitive() {
        assertThat(parse(formatter.format("WeCom", "t", "b", payload())).path("msgtype").asText())
                .isEqualTo("markdown");
        assertThat(parse(formatter.format("DingTalk", "t", "b", payload())).path("markdown").has("title"))
                .isTrue();
    }

    @Test
    @DisplayName("正文渲染：换行被压平（钉钉引用块会被换行截断），且含关键排查字段")
    void renderMarkdownFlattensNewlines() {
        String markdown = WebhookMessageFormatter.renderMarkdown("标题", payload());

        assertThat(markdown).contains("trace-1").contains("h5-tenant").contains("12");
        assertThat(markdown).contains("x is not a function");
        // 原始 message 里的换行必须被压平，否则钉钉的 > 引用块会从第二行断开
        assertThat(markdown).doesNotContain("is not a function\nat a.js");
    }

    @Test
    @DisplayName("超长 message 被截断（机器人有长度上限，超了整条消息会被拒）")
    void renderMarkdownTruncatesLongMessage() {
        WebhookPayload longPayload = new WebhookPayload(
                "ERROR", "x".repeat(1000), "t", "h5-tenant", "js", null, "fp", 1, LocalDateTime.now());
        String markdown = WebhookMessageFormatter.renderMarkdown("标题", longPayload);

        assertThat(markdown).contains("…");
        // 直接断言「没有原样带出 400 个 x」，比断言总长度更能说明截断确实发生
        assertThat(markdown).doesNotContain("x".repeat(400));
    }
}
