package com.ams.platform.observability.webhook;

import com.ams.platform.observability.ObservabilityProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 基于 Spring 自带 {@link RestClient} 的 Webhook 实现（设计 §10.2）。
 *
 * <p><strong>不新增依赖</strong>：{@code spring-boot-starter-web} 已带 Spring 6.1 的
 * {@code RestClient}，无需引入 OkHttp / HttpClient 等。
 *
 * <p>未配置（{@code enabled=false} 或地址为空）时直接返回，不做任何网络调用；
 * 这样调用点不必写 {@code if}，也不会有「忘了判断开关」导致空指针的风险。
 */
public class RestClientWebhookNotifier implements WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(RestClientWebhookNotifier.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final ObservabilityProperties.Alert config;
    private final WebhookMessageFormatter formatter;
    private final RestClient restClient;

    public RestClientWebhookNotifier(
            ObservabilityProperties.Alert config,
            WebhookMessageFormatter formatter,
            RestClient.Builder restClientBuilder) {
        this.config = config;
        this.formatter = formatter;
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactoryWithTimeout())
                .build();
    }

    @Override
    public void notify(String title, String body, WebhookPayload payload) {
        if (!config.sendable()) {
            return;
        }
        try {
            String json = formatter.format(config.webhookType(), title, body, payload);
            restClient.post()
                    .uri(config.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            // 告警失败只记日志：不重试、不落库、不向上抛 —— 通知不该影响任何业务流程
            log.warn("应用日志告警发送失败：{}", ex.getMessage());
        }
    }

    /**
     * 3 秒超时。
     *
     * <p>不设超时的后果很具体：Webhook 地址不可达时 TCP 连接会挂到操作系统默认超时
     * （可达数十秒），把派发线程长时间占住。告警是异步的，但线程池被占满后后续告警就会积压。
     */
    private static ClientHttpRequestFactory requestFactoryWithTimeout() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }
}
