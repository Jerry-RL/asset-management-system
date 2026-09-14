package com.ams.platform.observability.ingest;

import com.ams.common.exception.ErrorCode;
import com.ams.common.web.ApiResponse;
import com.ams.platform.observability.ObservabilityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 上报入口的<strong>解析前</strong>防护：请求体上限 + 按 IP 限流（设计 §7.2）。
 *
 * <p><strong>为什么必须在解析前</strong>：{@code @RequestBody} 的 JSON 解析发生在 Controller
 * 方法体之前。若把限流写在方法里，攻击者用畸形 JSON 就能绕过限流，并且每个请求都产生一次
 * 解析开销 —— 匿名端点上的这个差别很实在。
 */
public class AppLogIngestFilter extends OncePerRequestFilter {

    /** 只拦截这一个端点：这是本过滤器与全局 Filter 的唯一区别。 */
    public static final String PATH = "/api/v1/public/app-logs";

    private final ObservabilityProperties.Ingest config;
    private final IpRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public AppLogIngestFilter(
            ObservabilityProperties.Ingest config, IpRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 用 equals 而非 startsWith：本端点没有子路径，多匹配一个都是多余面
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        long contentLength = request.getContentLengthLong();
        if (contentLength > config.maxBodyBytes()) {
            // 不读 body：上限的意义就是「不要为了拒绝一个请求而先把它读进内存」
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.BAD_REQUEST,
                    "请求体过大");
            return;
        }

        if (!rateLimiter.tryAcquire(request.getRemoteAddr())) {
            // 返回 429 而不是抛异常：抛异常会落到 GlobalExceptionHandler.handleOther，
            // 被记成一行 app_type=backend 的日志，进而可能触发告警 —— 限流自激成告警风暴。
            reject(response, 429, ErrorCode.RATE_LIMITED, null);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = com.ams.common.web.TraceIdUtil.get();
        ApiResponse<Void> body = message == null
                ? ApiResponse.error(code, traceId)
                : ApiResponse.error(code, message, traceId);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
