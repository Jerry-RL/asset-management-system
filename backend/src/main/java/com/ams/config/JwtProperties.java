package com.ams.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（密钥由环境变量注入，禁止硬编码入库）。
 */
@ConfigurationProperties(prefix = "ams.jwt")
public record JwtProperties(
        String accessSecret,
        String refreshSecret,
        long accessExpirationMinutes,
        long refreshExpirationDays) {
}
