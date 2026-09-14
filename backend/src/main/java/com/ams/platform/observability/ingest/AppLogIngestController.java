package com.ams.platform.observability.ingest;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.dto.AppLogIngestRequest;
import com.ams.platform.observability.service.AppLogService;
import com.ams.platform.security.AuditedExempt;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端侧日志上报入口（设计 §7.1）。
 *
 * <p>路径落在 {@code /api/v1/public/**} 这已存在的免认证白名单内，因此：
 * <ul>
 *   <li>无需登录 —— 这是<strong>必需的</strong>：登录页自身的报错才是最需要上报的，
 *       而那时根本没有 token；</li>
 *   <li>不受 {@code ams.security.strict-perm} 影响，也不必改 {@code SecurityWhitelist}。</li>
 * </ul>
 *
 * <p>防护在 {@link AppLogIngestFilter}（体积/限流，解析前）与 {@link AppLogIngestGuard}
 * （白名单/截断，解析后）两处，本类只做编排。
 */
@RestController
@RequestMapping("/api/v1/public/app-logs")
public class AppLogIngestController {

    private final AppLogIngestGuard guard;
    private final AppLogService appLogService;

    public AppLogIngestController(AppLogIngestGuard guard, AppLogService appLogService) {
        this.guard = guard;
        this.appLogService = appLogService;
    }

    /**
     * 上报单条日志（无批量端点，见设计 D5）。
     *
     * <p>无论落库成功与否都返回 200：SDK 本就不读响应，而给一个从未被消费的错误码
     * 只会让日志里多出无意义的告警。真正需要暴露的输入问题（字段非法）才返回 400。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    @AuditedExempt("端侧日志上报：免登录白名单接口且高频，逐条落审计会让 operation_log 变成第二份 app_log")
    public ApiResponse<Void> ingest(
            @RequestBody AppLogIngestRequest request, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        LocalDateTime now = LocalDateTime.now();

        AppLog log;
        try {
            log = guard.normalize(request, clientIp, now);
        } catch (AppLogIngestGuard.RejectedException ex) {
            // 400：让 SDK 侧的问题暴露出来，而不是静默丢弃后「以为上报成功」
            throw new AppException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }

        // record 内部吞异常：DB 故障时仍然返回 200（日志已丢，但业务与上报方都不该受影响）
        appLogService.record(log, stackHeadFrom(request));
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    /**
     * 从 extra 里取堆栈，供服务端计算指纹用。
     *
     * <p>为什么要单独取：指纹使用「堆栈前 200 字符」，若指纹只按 message 计算，
     * 同一句 "Cannot read property 'x' of undefined" 会横跨无数个真实缺陷被合成一个指纹，
     * 聚合与告警冷却都会失去意义。
     */
    private String stackHeadFrom(AppLogIngestRequest request) {
        Object extra = request.getExtra();
        if (!(extra instanceof Map<?, ?> map)) {
            return null;
        }
        Object stack = map.get("stack");
        return stack == null ? null : String.valueOf(stack);
    }
}
