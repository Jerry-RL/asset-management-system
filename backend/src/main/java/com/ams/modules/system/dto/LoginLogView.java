package com.ams.modules.system.dto;

import java.time.LocalDateTime;

/**
 * 登录日志的查询视图（FR-COM-004 的「登录日志」）。
 *
 * <p><b>{@code result} 的真实取值是小写 {@code success} / {@code failed}</b>
 * （{@code AuthService.recordLoginLog} 的调用口径），筛选参数必须逐字一致。
 *
 * <p><b>{@code failReason} 语义重载</b>：成功行存的是<b>登录方式</b>
 * （{@code wechat} / {@code wechat_bind} / {@code wechat_bind_worker}），失败行才是失败原因。
 * 查询页把该列显示为「原因 / 方式」并加提示。这是既有数据模型问题，
 * 改表属独立决策（设计 §1.3），本模块只如实展示。
 */
public record LoginLogView(
        Long id,
        Long userId,
        String username,
        String ip,
        String result,
        String failReason,
        LocalDateTime createdAt) {
}
