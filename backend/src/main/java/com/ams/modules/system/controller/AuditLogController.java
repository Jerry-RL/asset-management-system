package com.ams.modules.system.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.system.dto.LoginLogView;
import com.ams.modules.system.dto.OperationLogView;
import com.ams.modules.system.service.AuditLogService;
import com.ams.platform.security.RequiresPerm;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志与登录日志查询接口（FR-COM-004）。
 *
 * <p><b>权限点刻意新建，不复用 {@code system.appLog:view}</b>（设计 D2）。
 * {@code app_log} 是运维日志（端侧 URL、UA、堆栈），这里返回的是审计数据
 * （用户名、IP、接口入参）。把审计数据挂在运维日志的权限下是一次隐蔽的提权 ——
 * app-log 设计 §8.1 已把「是否开放审计读取、用哪个权限点」定为独立决策，本控制器就是那个决策。
 *
 * <p><b>纯只读</b>：没有写接口、没有删除、没有清理。用户故事地图明确「操作日志不可删」，
 * NFR-DSEC-016 要求保留 ≥3 年。因此本控制器<b>不提供</b> {@code delete} 动作，
 * 迁移也不回填任何写动作。
 *
 * <p>类级 {@code system.operationLog:view} 同时覆盖两个 Tab（operation-logs / login-logs）：
 * {@code PermissionRegistry} 会扫类级与方法级注解，这里只有类级一处声明。
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiresPerm("system.operationLog:view")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 操作日志分页列表。
     *
     * <p>时间参数是 ISO <b>本地时间、不带时区后缀</b>（{@code YYYY-MM-DDTHH:mm:ss}），
     * 与全仓既有口径一致（{@code AppLogController}、{@code /ops-calendar}）。
     *
     * <p>注意：送 {@code ...Z} <b>不会</b>报 400，而是 offset 被静默丢弃
     * （{@code LocalDateTime.from} 忽略 {@code OFFSET_SECONDS}）—— 表现为查询窗口整体偏移几小时。
     * 前端 RangePicker 必须 {@code format('YYYY-MM-DDTHH:mm:ss')}，不要直接送 {@code toISOString()}。
     */
    @GetMapping("/operation-logs")
    public ApiResponse<PageResult<OperationLogView>> operationLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Long refId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(
                auditLogService.query(
                        from, to, username, module, success, keyword, traceId, refId, page, pageSize),
                TraceIdUtil.get());
    }

    /**
     * 单条详情（{@code detailJson} 已解析为对象）。
     *
     * <p>{@code /modules} 与 {@code /{id}} 并存：Spring 的路径匹配<b>字面量优先于模板</b>，
     * 与 {@code AppLogController} 的 {@code /stats} 和 {@code /{id}} 并存是同一先例。
     */
    @GetMapping("/operation-logs/{id}")
    public ApiResponse<OperationLogView> operationLogDetail(@PathVariable Long id) {
        return ApiResponse.ok(auditLogService.detail(id), TraceIdUtil.get());
    }

    /** 去重、升序的模块清单（筛选下拉的数据源）。 */
    @GetMapping("/operation-logs/modules")
    public ApiResponse<List<String>> operationLogModules() {
        return ApiResponse.ok(auditLogService.modules(), TraceIdUtil.get());
    }

    /**
     * 登录日志分页列表。
     *
     * <p>{@code result} 等值匹配小写 {@code success} / {@code failed}（AuthService 的写入口径）。
     */
    @GetMapping("/login-logs")
    public ApiResponse<PageResult<LoginLogView>> loginLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String ip,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(
                auditLogService.queryLoginLogs(from, to, username, result, ip, page, pageSize),
                TraceIdUtil.get());
    }
}
