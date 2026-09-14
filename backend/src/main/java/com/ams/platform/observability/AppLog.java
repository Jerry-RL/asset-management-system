package com.ams.platform.observability;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 应用日志（端侧报错 + 后端未捕获异常）。
 *
 * <p>与 {@code operation_log} 的关系见设计 D2：<strong>分表，不合并</strong>。
 * {@code operation_log} 是合规审计（带用户身份、长期保留）；本表是运维排查
 * （无身份、按 {@code ams.observability.retention-days} 自动过期）。两者靠
 * {@code trace_id} 关联，一次查询可串起「端侧报错 + 后端异常」。
 *
 * <p>{@code occurredAt} 与 {@code createdAt} 刻意分离：端侧时钟不可信，
 * 排序/查询/清理一律用服务端的 {@code createdAt}。
 */
@Data
@TableName("app_log")
public class AppLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全链路 ID：端侧请求级生成并放 X-Trace-Id，后端 TraceIdFilter 原样复用。 */
    private String traceId;

    /** ERROR / WARN / INFO（封闭词表，见 {@link AppLogLevel}）。 */
    private String level;

    /** 端或来源系统，见 {@link AppType}。 */
    private String appType;

    /** 错误来源，见 {@link AppLogSource}。 */
    private String source;

    /** 服务端计算的错误指纹（客户端不可伪造），用于聚合与告警冷却。 */
    private String fingerprint;

    private String message;

    /** 附加信息，JSON 字符串（V4 口径：全仓 JSONB 已改 TEXT）。 */
    private String extra;

    private String ua;

    private String url;

    /** 客户端自报，仅排查用，不作鉴权依据。 */
    private Long userId;

    /** 由服务端取 RemoteAddr 写入，不信任请求体。 */
    private String clientIp;

    /** 端侧发生时刻（时钟不可信，仅作线索）。 */
    private LocalDateTime occurredAt;

    /** 服务端落库时刻：排序、范围查询与清理的唯一依据。 */
    private LocalDateTime createdAt;
}
