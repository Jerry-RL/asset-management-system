package com.ams.platform.observability.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.platform.observability.ObservabilityProperties;
import com.ams.platform.observability.service.AppLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 定时清理任务测试（设计 D10 / §11）。
 *
 * <p>核心不变式：<strong>清理任务抛出的异常绝不能冒泡到调度线程</strong>。
 * {@code @Scheduled} 的默认行为是「本次执行出错就记 ERROR 并等下次 cron」，
 * 但如果异常逃出方法，在分布式/线程池调度器下会带来更难预料的后果，
 * 而且这里完全没必要 —— 清理失败下次重试即可。
 *
 * <p>另外锁住「保留天数确实取自配置」：写死 30 会让
 * {@code AMS_LOG_RETENTION_DAYS} 静默失效，表现为「改了配置但日志还是被删/没被删」。
 */
class AppLogPurgeJobTest {

    private final AppLogService appLogService = mock(AppLogService.class);

    private AppLogPurgeJob job(int retentionDays) {
        ObservabilityProperties properties = new ObservabilityProperties(
                retentionDays,
                new ObservabilityProperties.Ingest(null, null),
                new ObservabilityProperties.Alert(null, null, null, null, null, null));
        return new AppLogPurgeJob(appLogService, properties);
    }

    @Test
    @DisplayName("按配置的保留天数调用清理")
    void usesConfiguredRetentionDays() {
        when(appLogService.purgeExpired(anyInt())).thenReturn(5);

        job(7).purgeExpired();

        verify(appLogService).purgeExpired(7);
    }

    @Test
    @DisplayName("配置缺失时回落到 30 天（配置漏项不该让清理失控）")
    void defaultsTo30Days() {
        ObservabilityProperties properties =
                new ObservabilityProperties(null, null, null);
        when(appLogService.purgeExpired(anyInt())).thenReturn(0);

        new AppLogPurgeJob(appLogService, properties).purgeExpired();

        verify(appLogService).purgeExpired(30);
    }

    @Test
    @DisplayName("清理抛异常时不冒泡（下次调度重试即可）")
    void swallowsFailure() {
        when(appLogService.purgeExpired(anyInt()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThatCode(() -> job(30).purgeExpired()).doesNotThrowAnyException();
    }
}
