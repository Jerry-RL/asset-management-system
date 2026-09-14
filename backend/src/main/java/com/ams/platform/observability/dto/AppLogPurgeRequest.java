package com.ams.platform.observability.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 手动清理请求（{@code POST /api/v1/system/app-logs/purge}）。
 *
 * <p>{@code before} <strong>必填</strong>：这是防「一次误操作清空全表」的安全阀。
 * 没有它，一个不带任何参数的空请求就会删掉全部日志 —— 而日志恰恰是事后追查的唯一线索，
 * 删掉之后无法证明「当时发生了什么」。
 *
 * <p>{@code @NotNull} 让空请求在进入 Controller 时就以 400 快速失败；
 * {@code AppLogService.purge} 里还有第二道同样的校验（定时清理路径也走它）。
 */
@Data
public class AppLogPurgeRequest {

    /** 清理此时间<strong>之前</strong>（严格小于）创建的记录；必填。 */
    @NotNull(message = "必须指定清理时间边界 before")
    private LocalDateTime before;

    /** 可选：只清理某个端。 */
    private String appType;

    /** 可选：只清理某个级别。 */
    private String level;
}
