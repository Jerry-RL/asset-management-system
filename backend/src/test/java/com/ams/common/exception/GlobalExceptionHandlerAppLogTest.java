package com.ams.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.web.TraceIdUtil;
import com.ams.platform.observability.service.AppLogRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「哪些错误该进 app_log」的回归测试（设计 D9 / §9 / §17 R2）。
 *
 * <h2>为什么这条不变式必须由测试守住</h2>
 * {@code app_log} 是 ERROR 的告警来源。只要「客户端发错的东西」也会进来，就存在一个
 * <strong>匿名放大链</strong>：
 *
 * <pre>
 *   外部发一个畸形请求 → 服务端返回 500 → app_log 多一行 ERROR → 达阈值 → 告警刷屏
 * </pre>
 *
 * 触发成本极低（畸形 JSON、非法路径参数各占一个请求），而后果是把真人叫醒去看一个
 * 由攻击者制造的「故障」。因此这两条方向相反的断言缺一不可：
 * <ul>
 *   <li>客户端输入问题 → <b>不落库</b>（且返回 4xx 而非 5xx）；</li>
 *   <li>服务端真实故障 → <b>必须落库</b>（否则「全链路」是假的，端侧报错永远找不到后端对应项）。
 * </ul>
 */
class GlobalExceptionHandlerAppLogTest {

    private final AppLogRecorder recorder = mock(AppLogRecorder.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(recorder))
                .build();
    }

    // ---------------------------------------------------------------
    // 不该进 app_log 的：客户端问题
    // ---------------------------------------------------------------

    @Test
    @DisplayName("畸形 JSON -> 400 且不落库（否则匿名者可无限灌库刷告警）")
    void malformedJsonDoesNotRecord() throws Exception {
        mvc.perform(post("/probe/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(recorder, never()).recordBackendException(any(), anyString());
    }

    @Test
    @DisplayName("路径参数类型不匹配 -> 400 且不落库（仓里有匿名端点带 Long 路径变量）")
    void typeMismatchDoesNotRecord() throws Exception {
        mvc.perform(get("/probe/items/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(recorder, never()).recordBackendException(any(), anyString());
    }

    @Test
    @DisplayName("业务异常（AppException）-> 对应状态码且不落库（预期内的用户错误会淹没真信号）")
    void businessExceptionDoesNotRecord() throws Exception {
        mvc.perform(get("/probe/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        verify(recorder, never()).recordBackendException(any(), anyString());
    }

    // ---------------------------------------------------------------
    // 该进 app_log 的：服务端故障
    // ---------------------------------------------------------------

    @Test
    @DisplayName("未捕获异常 -> 500 且落库一次（端侧报错与后端异常靠这个 traceId 串起来）")
    void unhandledExceptionIsRecorded() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000));

        // traceId 用 any() 而不是 anyString()：没有任何 filter 时 TraceIdUtil.get() 是 null，
        // 而 anyString() 不匹配 null —— 用 anyString() 会让这条断言在「其实实现正确」时失败
        verify(recorder).recordBackendException(any(RuntimeException.class), any());
    }

    @Test
    @DisplayName("落库用的 traceId 与响应体取自同一处（不一致则全链路永远串不起来）")
    void recordedTraceIdIsTheResponseTraceId() throws Exception {
        mvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError());

        // 响应体的 traceId 与这里落库的 traceId 都是 TraceIdUtil.get() 的返回值。
        // standalone MockMvc 没有装 TraceIdFilter，因此这里取到的是未设置值（null）——
        // 关键不在值是什么，而在「两处必须是同一个来源」，否则端侧拿到的 traceId 在后端查不到。
        verify(recorder).recordBackendException(any(), eq(TraceIdUtil.get()));
    }

    /**
     * 只有测试用的探针控制器：制造三类异常，避免依赖真实业务接口。
     *
     * <p>{@code /echo} 刻意用 POJO 而不是 {@code String}：{@code String} 参数能让
     * {@code StringHttpMessageConverter} 接受任意内容，畸形 JSON 会被当成普通字符串绑定成功
     * （返回 200），就测不出 {@code HttpMessageNotReadableException} 这条路径了。
     */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        record EchoPayload(String value) {
        }

        @PostMapping("/echo")
        String echo(@RequestBody EchoPayload body) {
            return body == null ? null : body.value();
        }

        @GetMapping("/items/{id}")
        String item(@PathVariable Long id) {
            return String.valueOf(id);
        }

        @GetMapping("/business")
        String business() {
            throw new AppException(ErrorCode.CONFLICT, "状态冲突");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("模拟未捕获异常");
        }
    }
}
