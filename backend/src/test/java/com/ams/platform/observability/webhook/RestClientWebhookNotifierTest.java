package com.ams.platform.observability.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ams.platform.observability.ObservabilityProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Webhook 出口测试（设计 §10.2、§15）。
 *
 * <p>用 JDK 自带的 {@code HttpServer} 起一个真实的 localhost 端点，而不是 mock {@code RestClient}：
 * 后者只能验证「我调用了我自己写的方法」，验证不了报文是否真的是合法 JSON、是否真的带上了
 * 正确的 Content-Type —— 而「对方返回 200 但消息不出现」正是这类问题里最难查的一种。
 *
 * <p>同时锁住 §15 的最后两行：<strong>发送失败不得抛异常、不得重试</strong>。
 * 告警只是通知，失败绝不能回流到 ingest 链路或业务主流程。
 */
class RestClientWebhookNotifierTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    private String endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            requestCount.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/fail", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private WebhookPayload payload() {
        return new WebhookPayload("ERROR", "boom", "t-1", "h5-tenant", "js",
                "https://h5.example/x", "fp-1", 3, LocalDateTime.of(2026, 9, 12, 20, 0));
    }

    private RestClientWebhookNotifier notifier(String type, String url) {
        ObservabilityProperties.Alert config =
                new ObservabilityProperties.Alert(true, type, url, 1, 5, 5);
        return new RestClientWebhookNotifier(
                config, new WebhookMessageFormatter(new ObjectMapper()), RestClient.builder());
    }

    @Test
    @DisplayName("启用且配置地址 -> 真的发出请求，报文是合法 JSON 且带 JSON Content-Type")
    void sendsRealRequest() {
        notifier("generic", endpoint + "/hook").notify("标题", "正文", payload());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(lastContentType.get()).contains("application/json");
        assertThat(lastBody.get()).contains("\"event\":\"app_log_alert\"");
    }

    @Test
    @DisplayName("wecom 型 -> 线上收到企微格式（msgtype=markdown）")
    void sendsWecomFormat() {
        notifier("wecom", endpoint + "/hook").notify("标题", "### 标题", payload());

        assertThat(lastBody.get()).contains("\"msgtype\":\"markdown\"");
    }

    @Test
    @DisplayName("enabled=false -> 一个请求都不发（不因为忘判断开关就静默发出）")
    void disabledSendsNothing() {
        ObservabilityProperties.Alert disabled =
                new ObservabilityProperties.Alert(false, "generic", endpoint + "/hook", 1, 5, 5);
        new RestClientWebhookNotifier(
                        disabled, new WebhookMessageFormatter(new ObjectMapper()), RestClient.builder())
                .notify("标题", "正文", payload());

        assertThat(requestCount.get()).isZero();
    }

    @Test
    @DisplayName("enabled=true 但地址为空 -> 不发请求，也不抛异常")
    void blankUrlSendsNothing() {
        assertThatCode(() -> notifier("generic", "").notify("标题", "正文", payload()))
                .doesNotThrowAnyException();
        assertThat(requestCount.get()).isZero();
    }

    @Test
    @DisplayName("对端返回 500 -> 不抛异常（告警失败绝不能影响业务）")
    void non2xxDoesNotThrow() {
        assertThatCode(() -> notifier("generic", endpoint + "/fail").notify("标题", "正文", payload()))
                .doesNotThrowAnyException();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("地址不可达 -> 不抛异常、不重试（只 warn 后放弃）")
    void unreachableDoesNotThrowAndDoesNotRetry() {
        // 127.0.0.1:1 通常直接 ECONNREFUSED
        assertThatCode(() -> notifier("generic", "http://127.0.0.1:1/hook")
                        .notify("标题", "正文", payload()))
                .doesNotThrowAnyException();
        assertThat(requestCount.get()).isZero();
    }
}
