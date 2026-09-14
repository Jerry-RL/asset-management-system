package com.ams.platform.observability.dto;

import lombok.Data;

/**
 * 端侧上报的单条日志（{@code POST /api/v1/public/app-logs} 的请求体）。
 *
 * <p>字段全部为可选/宽松类型，因为：请求体来自公网、由 5 个端的 SDK 生成，
 * 任何字段都可能缺失或类型不对。<strong>校验与截断由 {@code AppLogIngestGuard} 统一做</strong>，
 * 这里不做 Bean Validation 注解 —— 那会在 JSON 绑定阶段抛异常，
 * 而畸形输入必须走「400 且不落库」这条路径（设计 §7.2.1）。
 */
@Data
public class AppLogIngestRequest {

    /** 请求级全链路 ID；缺失或格式非法时由服务端生成。 */
    private String traceId;

    /** ERROR / WARN / INFO；缺省 ERROR。 */
    private String level;

    /** admin-web / h5-tenant / h5-worker / tenant-mp / worker-mp。 */
    private String appType;

    /** js / promise / api。 */
    private String source;

    private String message;

    /** 任意附加信息，服务端序列化后按 16KB 上限截断。 */
    private Object extra;

    private String ua;

    private String url;

    /** 客户端自报，仅排查用，不作鉴权依据。 */
    private Long userId;

    /** ISO-8601 字符串；解析失败或缺失时用服务端当前时间。 */
    private String occurredAt;
}
