package com.ams.platform.security;

import com.ams.common.web.TraceIdUtil;
import com.ams.modules.system.entity.OperationLog;
import com.ams.modules.system.mapper.OperationLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作日志切面：记录用户、模块、动作、入参、traceId。
 */
@Aspect
@Component
public class OperationLogAspect {

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    public OperationLogAspect(OperationLogMapper operationLogMapper, ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            record(audited, pjp, null);
            return result;
        } catch (Throwable t) {
            record(audited, pjp, t.getMessage());
            throw t;
        } finally {
            // 无需额外处理；duration 可放入 detail
            long duration = System.currentTimeMillis() - start;
        }
    }

    private void record(Audited audited, ProceedingJoinPoint pjp, String error) {
        try {
            LoginUser user = SecurityUtils.current();
            OperationLog log = new OperationLog();
            log.setUserId(user == null ? null : user.getUserId());
            log.setUsername(user == null ? null : user.getUsername());
            log.setModule(audited.module());
            log.setAction(audited.action());
            log.setTraceId(TraceIdUtil.get());
            log.setCreatedAt(LocalDateTime.now());
            HttpServletRequest request = currentRequest();
            if (request != null) {
                log.setIp(request.getRemoteAddr());
            }
            // 入参摘要（脱敏：不记录 password/token 等）
            try {
                Object[] args = pjp.getArgs();
                log.setDetailJson(objectMapper.writeValueAsString(
                        java.util.Map.of("args", sanitize(args), "error", error == null ? "" : error)));
            } catch (Exception ignored) {
                log.setDetailJson("{}");
            }
            operationLogMapper.insert(log);
        } catch (Exception ignored) {
            // 日志失败不影响业务
        }
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
