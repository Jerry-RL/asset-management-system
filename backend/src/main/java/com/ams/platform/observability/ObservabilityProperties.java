package com.ams.platform.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用日志与告警配置（设计 §13.1）。
 *
 * @param retentionDays 保留天数：超过该天数的记录由 {@code AppLogPurgeJob} 清理
 * @param ingest        上报入口的防护参数
 * @param alert         告警参数
 */
@ConfigurationProperties(prefix = "ams.observability")
public record ObservabilityProperties(
        Integer retentionDays,
        Ingest ingest,
        Alert alert) {

    /** 缺省值集中在这里，避免各处再写一遍 {@code == null} 兜底（配置缺失不应让应用起不来）。 */
    public ObservabilityProperties {
        retentionDays = retentionDays == null ? 30 : retentionDays;
        ingest = ingest == null ? new Ingest(null, null) : ingest;
        alert = alert == null ? new Alert(null, null, null, null, null, null) : alert;
    }

    /**
     * 上报入口防护。
     *
     * @param maxBodyBytes        请求体上限（字节）；超出返回 413
     * @param rateLimitPerMinute  单 IP 每分钟允许的上报次数；超出返回 429
     */
    public record Ingest(Integer maxBodyBytes, Integer rateLimitPerMinute) {

        public Ingest {
            maxBodyBytes = maxBodyBytes == null ? 65_536 : maxBodyBytes;
            rateLimitPerMinute = rateLimitPerMinute == null ? 120 : rateLimitPerMinute;
        }
    }

    /**
     * 告警参数。
     *
     * @param enabled          是否启用（默认 false：未配置 Webhook 时不该静默哑掉）
     * @param webhookType      {@code wecom} | {@code dingtalk} | {@code generic}
     * @param webhookUrl       机器人地址；{@code enabled=true} 但为空时启动告警提示
     * @param threshold        窗口内 ERROR 次数达到该值即触发
     * @param windowMinutes    计数窗口（分钟）
     * @param cooldownMinutes  同一指纹的冷却时间（分钟）——防轰炸
     */
    public record Alert(
            Boolean enabled,
            String webhookType,
            String webhookUrl,
            Integer threshold,
            Integer windowMinutes,
            Integer cooldownMinutes) {

        public Alert {
            enabled = enabled != null && enabled;
            webhookType = (webhookType == null || webhookType.isBlank()) ? "generic" : webhookType.trim();
            threshold = threshold == null || threshold < 1 ? 10 : threshold;
            windowMinutes = windowMinutes == null || windowMinutes < 1 ? 5 : windowMinutes;
            cooldownMinutes = cooldownMinutes == null || cooldownMinutes < 1 ? 5 : cooldownMinutes;
        }

        /** 是否具备发送条件（启用且配了地址）。 */
        public boolean sendable() {
            return enabled && webhookUrl != null && !webhookUrl.isBlank();
        }
    }
}
