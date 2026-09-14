package com.ams.platform.observability.query;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.platform.observability.dto.AppLogPurgeRequest;
import com.ams.platform.observability.dto.AppLogStats;
import com.ams.platform.observability.dto.AppLogView;
import com.ams.platform.observability.service.AppLogService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RequiresPerm;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用日志查询接口（设计 §8）。
 *
 * <p>类级 {@code system.appLog:view}：读日志需要权限，不是登录即可见 ——
 * 日志含端侧 URL、UA、用户自报 ID 与异常堆栈，属运维数据。
 *
 * <p>{@code purge} 用方法级 {@code system.appLog:delete} 覆盖类级声明
 * （{@code PermissionRegistry} 同时扫描类级与方法级注解，方法级优先）。
 */
@RestController
@RequestMapping("/api/v1/system/app-logs")
@RequiresPerm("system.appLog:view")
public class AppLogController {

    private final AppLogService appLogService;

    public AppLogController(AppLogService appLogService) {
        this.appLogService = appLogService;
    }

    /** 分页查询；时间范围作用于服务端 created_at（端侧时钟不可信）。 */
    @GetMapping
    public ApiResponse<PageResult<AppLogView>> list(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String appType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(
                appLogService.query(level, appType, source, keyword, traceId, from, to, page, pageSize),
                TraceIdUtil.get());
    }

    /** 单条详情（extra 已解析为 JSON 对象）。 */
    @GetMapping("/{id}")
    public ApiResponse<AppLogView> detail(@PathVariable Long id) {
        return ApiResponse.ok(appLogService.detail(id), TraceIdUtil.get());
    }

    /**
     * 同 traceId 的全链路记录，按发生时间升序。
     *
     * <p>只返回 {@code app_log}，不含 {@code operation_log}（见设计 §8.1：
     * 审计表目前全仓只写不读，此处展示等于给它开一条新的读取通道）。
     */
    @GetMapping("/trace/{traceId}")
    public ApiResponse<List<AppLogView>> trace(@PathVariable String traceId) {
        return ApiResponse.ok(appLogService.trace(traceId), TraceIdUtil.get());
    }

    /** 近 24h 统计（顶部统计条）。 */
    @GetMapping("/stats")
    public ApiResponse<AppLogStats> stats() {
        return ApiResponse.ok(appLogService.stats(), TraceIdUtil.get());
    }

    /**
     * 手动清理。
     *
     * <p>用 POST 而非 DELETE：{@code DELETE} 带请求体在各层代理上的处理不一致，语义也模糊；
     * 这里是一次「带条件的批量删除动作」，POST + 明确的请求体更不容易被中间件吃掉参数。
     *
     * @return {@code {"deleted": n}} —— 返回行数让操作者能确认「确实删了东西」，
     *         静默成功会让人怀疑到底生效没有
     */
    @PostMapping("/purge")
    @RequiresPerm("system.appLog:delete")
    @Audited(module = "system", action = "app_log_purge")
    public ApiResponse<Map<String, Integer>> purge(@Valid @RequestBody AppLogPurgeRequest request) {
        int deleted = appLogService.purge(request);
        return ApiResponse.ok(Map.of("deleted", deleted), TraceIdUtil.get());
    }
}
