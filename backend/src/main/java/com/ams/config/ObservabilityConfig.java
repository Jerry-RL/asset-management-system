package com.ams.config;

import com.ams.platform.observability.ObservabilityProperties;
import com.ams.platform.observability.ingest.AppLogIngestFilter;
import com.ams.platform.observability.ingest.IpRateLimiter;
import com.ams.platform.observability.webhook.RestClientWebhookNotifier;
import com.ams.platform.observability.webhook.WebhookMessageFormatter;
import com.ams.platform.observability.webhook.WebhookNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;

/**
 * 应用日志模块的装配（设计 §7.2、§10.2、§13）。
 *
 * <p>用 {@link FilterRegistrationBean} 而不是给 Filter 加 {@code @Component}，理由有二：
 * <ol>
 *   <li>只挂在上报端点上，其余请求连一次 {@code shouldNotFilter} 判断都省掉；</li>
 *   <li>顺序可控 —— 必须排在 Spring Security 过滤器链<strong>之后</strong>，才能拿到
 *       {@code TraceIdFilter} 写好的 traceId。</li>
 * </ol>
 */
@Configuration
public class ObservabilityConfig {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfig.class);

    @Bean
    public FilterRegistrationBean<AppLogIngestFilter> appLogIngestFilter(
            ObservabilityProperties properties, ObjectMapper objectMapper) {
        ObservabilityProperties.Ingest ingest = properties.ingest();
        IpRateLimiter rateLimiter = new IpRateLimiter(ingest.rateLimitPerMinute());
        AppLogIngestFilter filter = new AppLogIngestFilter(ingest, rateLimiter, objectMapper);

        FilterRegistrationBean<AppLogIngestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(AppLogIngestFilter.PATH);
        // 排在安全链（默认 -100）之后：需要 TraceIdFilter 已写入 MDC/ThreadLocal，
        // 否则拒绝响应里的 traceId 是空的，排查时对不上
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public WebhookMessageFormatter webhookMessageFormatter(ObjectMapper objectMapper) {
        return new WebhookMessageFormatter(objectMapper);
    }

    @Bean
    public WebhookNotifier webhookNotifier(
            ObservabilityProperties properties,
            WebhookMessageFormatter formatter,
            RestClient.Builder restClientBuilder) {
        ObservabilityProperties.Alert alert = properties.alert();
        if (alert.enabled() && !alert.sendable()) {
            // 明确告警而不是静默：enable 了却没配地址，会让人以为「没告警 = 没错误」
            log.warn("应用日志告警已开启但未配置 ams.observability.alert.webhook-url，告警将只写日志不发送");
        }
        return new RestClientWebhookNotifier(alert, formatter, restClientBuilder);
    }
}
