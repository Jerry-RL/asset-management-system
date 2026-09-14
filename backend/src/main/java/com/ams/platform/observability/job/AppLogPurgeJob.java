package com.ams.platform.observability.job;

import com.ams.platform.observability.ObservabilityProperties;
import com.ams.platform.observability.service.AppLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 应用日志的过期清理（设计 §11、D10）。
 *
 * <p>用 {@code @Scheduled} 而不是 JobRunr：本仓 13 个周期任务<strong>全部</strong>在
 * {@link com.ams.platform.job.ScheduledJobs} 里走 {@code @Scheduled}；JobRunr 虽已在
 * {@code pom.xml} 且 {@code enabled=true}，但全仓零使用。为一个清理任务首次启用一条从未被
 * 验证过的运行路径，风险高于收益（设计 §17 R4）。将来统一迁移也很容易，就一个方法。
 *
 * <p>{@code ams.scheduling.enabled=false}（测试环境）时不会执行，
 * 测试可直接调用 {@link AppLogService#purgeExpired(int)} 断言行为。
 */
@Component
public class AppLogPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AppLogPurgeJob.class);

    private final AppLogService appLogService;
    private final ObservabilityProperties properties;

    public AppLogPurgeJob(AppLogService appLogService, ObservabilityProperties properties) {
        this.appLogService = appLogService;
        this.properties = properties;
    }

    /**
     * 每日 03:30 清理超期日志。
     *
     * <p>选这个时间是刻意的：{@code ScheduledJobs} 的业务任务集中在 01:00–08:00，
     * 03:30 落在 03:00（滞纳金）与 04:00（合同到期扫描）之间，避开它们的数据库压力。
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpired() {
        int retentionDays = properties.retentionDays();
        try {
            int deleted = appLogService.purgeExpired(retentionDays);
            if (deleted > 0) {
                log.info("app-log purge: 清理 {} 天前的日志 {} 行", retentionDays, deleted);
            }
        } catch (Exception ex) {
            // 清理失败只记日志：下次调度会重试，不该让异常冒泡到调度线程
            log.warn("app-log purge 失败（下次调度重试）：{}", ex.getMessage());
        }
    }
}
