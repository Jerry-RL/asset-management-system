package com.ams.platform.security;

import com.ams.common.web.TraceIdUtil;
import com.ams.modules.system.entity.OperationLog;
import com.ams.modules.system.mapper.OperationLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作日志切面：记录用户、模块、动作、被操作对象（{@code ref_id}）、入参、traceId、成败。
 *
 * <p>{@code ref_id} 由 {@link AuditRefIdResolver} 推断（规则与「刻意不猜」的边界见该类注释）：
 * 它让「某个对象上发生过什么」可以用等值条件查，而不必对 {@code detail_json} 做 {@code LIKE}。
 *
 * <p>两条硬约束（改动前请先读完）：
 *
 * <ol>
 *   <li><b>不用 {@code REQUIRES_NEW}</b>。{@code @Audited} 落在 Controller 上，切面在 MVC 层
 *       执行、此处没有事务上下文，普通 {@code insert} 即自动提交。加 {@code REQUIRES_NEW}
 *       只会多占一个连接。</li>
 *   <li><b>禁止自激</b>：{@link #record} 内部 {@code catch (Throwable)} 且只调
 *       {@code log.warn}，不得调用任何会再次抛未捕获异常的组件 —— 审计故障绝不能变成业务
 *       500，但也绝不能静默（审计是合规证据，丢失必须留下可排查的痕迹）。</li>
 * </ol>
 *
 * <p><b>事务边界的既有行为</b>：切面在 Controller 层记录，若某 Service 方法自身回滚，
 * 这里仍会记一行 {@code success=false} —— 这是期望语义（「尝试过的操作」也要留痕）。
 * 刻意<b>不</b>改成「只在提交成功后记录」：那会漏掉全部失败尝试，而失败尝试恰恰是审计
 * 最想看到的。
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    /** {@code error} 列的宽度上限，超出截断（V51 的 DDL 是 VARCHAR(500)）。 */
    private static final int ERROR_MAX_LENGTH = 500;

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    public OperationLogAspect(OperationLogMapper operationLogMapper, ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        try {
            Object result = pjp.proceed();
            record(audited, pjp, result, true, null);
            return result;
        } catch (Throwable t) {
            // 成败由调用点**显式**传入，不由 `error == null` 反推：
            // 异常的 getMessage() 本身就可能为 null（如裸 NullPointerException），
            // 反推会把失败的操作记成成功 —— 审计里最不能出错的方向。
            record(audited, pjp, null, false, t.getMessage());
            // 异常必须继续上抛：审计绝不改变业务行为
            throw t;
        }
    }

    /**
     * @param result  方法返回值，用于推断 {@code ref_id}（失败路径上传 {@code null}）
     * @param success 显式传入，理由见 {@link #around}
     */
    private void record(Audited audited, ProceedingJoinPoint pjp, Object result, boolean success,
            String error) {
        try {
            LoginUser user = SecurityUtils.current();
            // 局部变量刻意不叫 `log`：本类现在有 SLF4J 的 logger 同名，会把 warn 写串
            OperationLog entry = new OperationLog();
            entry.setUserId(user == null ? null : user.getUserId());
            entry.setUsername(user == null ? null : user.getUsername());
            entry.setModule(audited.module());
            entry.setAction(audited.action());
            entry.setTraceId(TraceIdUtil.get());
            entry.setCreatedAt(LocalDateTime.now());
            entry.setSuccess(success);
            entry.setError(truncate(error, ERROR_MAX_LENGTH));
            // ref_id：推断不出就留 NULL。单独包一层 try 是因为这一段一旦抛出，
            // 丢掉的就不是一个字段而是**整条**审计记录 —— 推断失败最多只是少一条线索
            try {
                entry.setRefId(AuditRefIdResolver.resolve(pjp, result));
            } catch (RuntimeException ignored) {
                entry.setRefId(null);
            }
            HttpServletRequest request = currentRequest();
            if (request != null) {
                entry.setIp(request.getRemoteAddr());
            }
            // 入参摘要（脱敏：不记录 password/token 等）
            // detail_json 只放 args：error 已独立成列，两处都写会产生两套口径
            try {
                entry.setDetailJson(objectMapper.writeValueAsString(
                        Map.of("args", sanitize(pjp.getArgs()))));
            } catch (Exception ignored) {
                entry.setDetailJson("{}");
            }
            operationLogMapper.insert(entry);
        } catch (Throwable t) {
            // 审计写入失败绝不能影响业务，但也绝不能静默：
            // 审计是合规证据，静默丢失会让「合规上确实记了」变成一个无法证伪的假设
            log.warn("操作审计落库失败（不影响业务）：module={} action={} traceId={}",
                    audited.module(), audited.action(), TraceIdUtil.get(), t);
        }
    }

    /** 截断到列宽；{@code null} 原样返回（成功行没有失败原因）。 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Object sanitize(Object[] args) {
        if (args == null) {
            return java.util.List.of();
        }
        java.util.List<Object> list = new java.util.ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof HttpServletRequest) {
                continue;
            }
            list.add(arg == null ? null : String.valueOf(arg).replaceAll(
                    "(?i)(password|token|idNo|id_no|phone)=\"?[^\"&,}]*\"?", "$1=***"));
        }
        return list;
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    // 供 MethodSignature 引用（保留类型安全）
    @SuppressWarnings("unused")
    private MethodSignature signature(ProceedingJoinPoint pjp) {
        return (MethodSignature) pjp.getSignature();
    }
}
