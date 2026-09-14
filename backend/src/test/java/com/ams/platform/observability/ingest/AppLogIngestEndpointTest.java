package com.ams.platform.observability.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.platform.observability.AppLog;
import com.ams.platform.observability.ObservabilityProperties;
import com.ams.platform.observability.service.AppLogRecorder;
import com.ams.platform.observability.service.AppLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 上报端点端到端测试（设计 §7）。
 *
 * <p>用 standalone MockMvc 装配<strong>真实的</strong> Filter + Guard + Controller +
 * 异常处理器，只把 {@code AppLogService} 换成桩（不依赖数据库）。这样测的是真实的
 * 「请求进入 → 防护 → 校验 → 归一化 → 落库调用」链路，而不是把每一段拆开单独测后
 * 谁也不知道装起来是否还能工作。
 *
 * <p>重点覆盖三类<strong>放大面</strong>：畸形 JSON、体积超限、限流 —— 它们在匿名端点上
 * 都能被外部触发，且一旦处理不当就会「外部发一个请求 → 内部写一行 ERROR → 触发告警」。
 */
class AppLogIngestEndpointTest {

    private static final ObservabilityProperties.Ingest INGEST =
            new ObservabilityProperties.Ingest(512, 120);

    private final AppLogService appLogService = mock(AppLogService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mvc(ObservabilityProperties.Ingest ingest) {
        IpRateLimiter limiter = new IpRateLimiter(ingest.rateLimitPerMinute());
        return mvc(ingest, limiter);
    }

    private MockMvc mvc(ObservabilityProperties.Ingest ingest, IpRateLimiter limiter) {
        AppLogIngestFilter filter =
                new AppLogIngestFilter(ingest, limiter, objectMapper);
        AppLogIngestController controller =
                new AppLogIngestController(new AppLogIngestGuard(objectMapper), appLogService);
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .setControllerAdvice(new GlobalExceptionHandler(mock(AppLogRecorder.class)))
                .build();
    }

    private static final String VALID_BODY = """
            {
              "traceId": "3f2a1c9b8e7d6f5a4b3c2d1e0f9a8b7c",
              "level": "ERROR",
              "appType": "h5-tenant",
              "source": "api",
              "message": "GET /api/v1/bills 失败",
              "extra": {"method": "GET", "status": 500, "stack": "at api.ts:10:3"},
              "ua": "Mozilla/5.0",
              "url": "https://h5.example/bills"
            }
            """;

    @Test
    @DisplayName("合法上报 -> 200，且归一化后的字段正确（含服务端 IP）")
    void acceptsValidReport() throws Exception {
        mvc(INGEST)
                .perform(post(AppLogIngestFilter.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<AppLog> saved = ArgumentCaptor.forClass(AppLog.class);
        ArgumentCaptor<String> stackHead = ArgumentCaptor.forClass(String.class);
        verify(appLogService).record(saved.capture(), stackHead.capture());

        assertThat(saved.getValue().getAppType()).isEqualTo("h5-tenant");
        assertThat(saved.getValue().getSource()).isEqualTo("api");
        assertThat(saved.getValue().getLevel()).isEqualTo("ERROR");
        assertThat(saved.getValue().getTraceId()).isEqualTo("3f2a1c9b8e7d6f5a4b3c2d1e0f9a8b7c");
        // client_ip 取服务端看到的来源，不看请求体（请求体里的任何 ip 字段都不可信）
        assertThat(saved.getValue().getClientIp()).isNotNull();
        // 堆栈必须传给 Service 用于指纹计算：只按 message 算指纹会把无数缺陷合成一个
        assertThat(stackHead.getValue()).isEqualTo("at api.ts:10:3");
    }

    @Test
    @DisplayName("畸形 JSON -> 400，且不落库、不生成后端异常（否则匿名者可无限灌库刷告警）")
    void malformedJsonIsRejectedWithoutRecording() throws Exception {
        mvc(INGEST)
                .perform(post(AppLogIngestFilter.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appType\": \"h5-tenant\","))
                .andExpect(status().isBadRequest());

        verify(appLogService, never()).record(any(), any());
    }

    @Test
    @DisplayName("非法 appType / source -> 400，不落库")
    void invalidEnumsAreRejected() throws Exception {
        for (String body : new String[] {
                bodyWith("appType", "h5-tenant", "unknown-app"),
                bodyWith("appType", "h5-tenant", "backend"),
                bodyWith("source", "api", "sql"),
                bodyWith("level", "ERROR", "FATAL")}) {
            mvc(INGEST)
                    .perform(post(AppLogIngestFilter.PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(appLogService, never()).record(any(), any());
    }

    @Test
    @DisplayName("请求体超限 -> 413，且服务从未被调用")
    void oversizedBodyIsRejected() throws Exception {
        String huge = "{\"appType\":\"h5-tenant\",\"source\":\"js\",\"message\":\""
                + "x".repeat(2000) + "\"}";
        assertThat(huge.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(INGEST.maxBodyBytes());

        mvc(INGEST)
                .perform(post(AppLogIngestFilter.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(huge))
                .andExpect(status().isPayloadTooLarge());

        verify(appLogService, never()).record(any(), any());
    }

    @Test
    @DisplayName("超出限流 -> 429，不落库、也不抛异常（抛异常会被记成后端故障，形成告警自激）")
    void rateLimitedIsRejected() throws Exception {
        ObservabilityProperties.Ingest tiny = new ObservabilityProperties.Ingest(4096, 2);
        MockMvc mockMvc = mvc(tiny);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(AppLogIngestFilter.PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post(AppLogIngestFilter.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests());

        verify(appLogService, times(2)).record(any(), any());
    }

    @Test
    @DisplayName("落库失败/DB 故障仍返回 200（日志系统故障不得变成上报方的故障）")
    void returnsOkEvenWhenRecordFails() throws Exception {
        when(appLogService.record(any(), any())).thenReturn(null);

        mvc(INGEST)
                .perform(post(AppLogIngestFilter.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("traceId 非法时服务端生成，仍然落库而不是拒绝")
    void generatesTraceIdWhenInvalid() throws Exception {
        String body = """
                {"appType":"worker-mp","source":"js","message":"boom","traceId":"!!bad!!"}
                """;

        mvc(INGEST)
                .perform(post(AppLogIngestFilter.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<AppLog> saved = ArgumentCaptor.forClass(AppLog.class);
        verify(appLogService).record(saved.capture(), isNull());
        assertThat(saved.getValue().getTraceId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("只拦截上报路径：其它路径不进入限流逻辑")
    void filterOnlyGuardsItsOwnPath() {
        AppLogIngestFilterExposed filter = new AppLogIngestFilterExposed(
                INGEST, new IpRateLimiter(1), objectMapper);

        // shouldNotFilter 是 protected：用测试子类暴露出来，避免为了断言「不拦截」
        // 而依赖 standalone MockMvc 对未映射路径的具体行为（那属于框架细节）
        assertThat(filter.shouldSkip("/api/v1/public/app-logs")).isFalse();
        assertThat(filter.shouldSkip("/api/v1/public/assets/1/scan")).isTrue();
        assertThat(filter.shouldSkip("/api/v1/system/app-logs")).isTrue();
        // 不做前缀匹配：本端点没有子路径，多匹配一个都是多余的攻击面
        assertThat(filter.shouldSkip("/api/v1/public/app-logs/extra")).isTrue();
    }

    private static String bodyWith(String field, String originalValue, String newValue) {
        return VALID_BODY.replace(
                "\"" + field + "\": \"" + originalValue + "\"",
                "\"" + field + "\": \"" + newValue + "\"");
    }

    /** 暴露 protected 的 {@code shouldNotFilter}，便于直接断言路径匹配规则。 */
    private static final class AppLogIngestFilterExposed extends AppLogIngestFilter {
        AppLogIngestFilterExposed(
                ObservabilityProperties.Ingest config, IpRateLimiter limiter, ObjectMapper mapper) {
            super(config, limiter, mapper);
        }

        boolean shouldSkip(String uri) {
            return shouldNotFilter(new org.springframework.mock.web.MockHttpServletRequest("POST", uri));
        }
    }
}
