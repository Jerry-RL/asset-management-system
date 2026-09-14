package com.ams.platform.observability.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.common.web.PageResult;
import com.ams.platform.observability.dto.AppLogPurgeRequest;
import com.ams.platform.observability.service.AppLogRecorder;
import com.ams.platform.observability.service.AppLogService;
import com.ams.platform.security.PermissionInterceptor;
import com.ams.platform.security.RbacService;
import com.ams.support.RbacFixtures;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 查询接口的权限与参数校验（设计 §8、§16.1）。
 *
 * <p>用真实控制器 + 真实拦截器：权限码是从控制器自己身上的 {@code @RequiresPerm} 解析出来的
 * （不是测试里手抄的字符串），因此注解被删或动作被改都会让用例变红。
 *
 * <p>三条重点：
 * <ol>
 *   <li>类级 {@code system.appLog:view} 覆盖列表/详情/trace/stats —— 日志含 URL、UA、
 *       自报 ID 与堆栈，不是「登录即可见」；</li>
 *   <li>{@code purge} 的方法级 {@code system.appLog:delete} <strong>覆盖</strong>了类级声明 ——
 *       只有 view 的账号必须 403，否则「能看日志」就等于「能删日志」；</li>
 *   <li>{@code purge} 缺 {@code before} → 400，且服务层从未被调用（安全阀在服务层之前就该生效）。</li>
 * </ol>
 */
class AppLogControllerPermissionTest {

    private RbacService rbacService;
    private AppLogService appLogService;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        appLogService = mock(AppLogService.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AppLogController(appLogService))
                .addInterceptors(new PermissionInterceptor(rbacService, false))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AppLogRecorder.class)))
                .build();
    }

    private void login(Set<String> permissions) {
        var user = com.ams.platform.security.LoginUser.builder()
                .userId(9002L)
                .username("log-probe")
                .name("日志探针")
                .companyId(2L)
                .homeCompanyId(2L)
                .departmentId(1L)
                .clientType("admin")
                .roles(Set.of("operator"))
                .permissions(permissions)
                .dataScope("all")
                .companyScoped(false)
                .excludedCompanyIds(Set.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Nested
    @DisplayName("读接口 / 类级 system.appLog:view")
    class ReadEndpoints {

        @Test
        @DisplayName("无 view 权限 -> 全部读接口 403，且服务层从未被调用")
        void withoutViewIsForbidden() throws Exception {
            login(Set.of());
            when(appLogService.query(any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                    .thenReturn(PageResult.of(List.of(), 0, 1, 20));

            mvc().perform(get("/api/v1/system/app-logs"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));
            mvc().perform(get("/api/v1/system/app-logs/1")).andExpect(status().isForbidden());
            mvc().perform(get("/api/v1/system/app-logs/trace/abc")).andExpect(status().isForbidden());
            mvc().perform(get("/api/v1/system/app-logs/stats")).andExpect(status().isForbidden());

            verify(appLogService, never()).query(any(), any(), any(), any(), any(), any(), any(),
                    anyLong(), anyLong());
            verify(appLogService, never()).detail(any());
            verify(appLogService, never()).trace(any());
            verify(appLogService, never()).stats();
        }

        @Test
        @DisplayName("有 view 权限 -> 列表 / 详情 / trace 放行")
        void withViewIsAllowed() throws Exception {
            login(Set.of("system.appLog:view"));
            when(appLogService.query(any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                    .thenReturn(PageResult.of(List.of(), 0, 1, 20));
            when(appLogService.trace("abc")).thenReturn(List.of());

            mvc().perform(get("/api/v1/system/app-logs")).andExpect(status().isOk());
            mvc().perform(get("/api/v1/system/app-logs/trace/abc")).andExpect(status().isOk());
            mvc().perform(get("/api/v1/system/app-logs/stats")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("清理 / 方法级 system.appLog:delete 覆盖类级 view")
    class Purge {

        @Test
        @DisplayName("只有 view 权限 -> purge 403（能看日志 ≠ 能删日志）")
        void viewIsNotEnoughForPurge() throws Exception {
            login(Set.of("system.appLog:view"));

            mvc().perform(post("/api/v1/system/app-logs/purge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"before\":\"2026-01-01T00:00:00\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));

            verify(appLogService, never()).purge(any());
        }

        @Test
        @DisplayName("有 delete 权限 -> 放行并返回删除行数（静默成功会让人怀疑到底生效没有）")
        void deletePermissionAllowsPurge() throws Exception {
            login(Set.of("system.appLog:view", "system.appLog:delete"));
            when(appLogService.purge(any())).thenReturn(7);

            mvc().perform(post("/api/v1/system/app-logs/purge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"before\":\"2026-01-01T00:00:00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deleted").value(7));
        }

        @Test
        @DisplayName("缺 before -> 400 且服务层未被调用（防「一次误操作清空全表」）")
        void missingBeforeIsRejected() throws Exception {
            login(Set.of("system.appLog:view", "system.appLog:delete"));

            mvc().perform(post("/api/v1/system/app-logs/purge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verify(appLogService, never()).purge(any());
        }

        @Test
        @DisplayName("空 body -> 400 而不是 500（畸形输入不该被记成服务端故障）")
        void emptyBodyIsRejected() throws Exception {
            login(Set.of("system.appLog:view", "system.appLog:delete"));

            mvc().perform(post("/api/v1/system/app-logs/purge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(status().isBadRequest());

            verify(appLogService, never()).purge(any());
        }
    }

    @Test
    @DisplayName("purge 把请求体原样交给服务层（时间边界由服务层二次校验）")
    void purgePassesRequestToService() throws Exception {
        login(Set.of("system.appLog:view", "system.appLog:delete"));
        when(appLogService.purge(any())).thenReturn(0);

        mvc().perform(post("/api/v1/system/app-logs/purge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"before\":\"2026-01-01T00:00:00\",\"appType\":\"h5-tenant\"}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(AppLogPurgeRequest.class);
        verify(appLogService).purge(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBefore()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getAppType())
                .isEqualTo("h5-tenant");
    }
}
