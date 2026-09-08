package com.ams.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关：生产默认开启，测试环境通过 ams.scheduling.enabled=false 关闭。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ams.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
