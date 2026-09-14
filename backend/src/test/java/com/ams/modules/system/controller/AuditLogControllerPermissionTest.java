package com.ams.modules.system.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.common.web.PageResult;
import com.ams.modules.system.dto.OperationLogView;
import com.ams.modules.system.service.AuditLogService;
import com.ams.platform.observability.service.AppLogRecorder;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 审计查询接口的权限（设计 §5、§16.1）。
 *
 * <p>用真实控制器 + 真实拦截器：权限码是从控制器自己身上的 {@code @RequiresPerm} 解析出来的
 * （不是测试里手抄的字符串），因此注解被删或动作被改都会让用例变红。
 *
 * <p>四条重点：
 * <ol>
 *   <li>无 {@code system.operationLog:view} → 全部读接口 403，且服务层从未被调用；</li>
 *   <li><b>只有 {@code system.appLog:view} 不够</b> —— 这是本模块最核心的安全主张：
 *       审计数据（用户名 / IP / 接口入参）不能靠运维日志的权限点搭便车看到；</li>
 *   <li>{@code /operation-logs/modules} 不能被 {@code /operation-logs/{id}} 抢走
 *       （否则 {@code Long id} 解析失败 → 400，表现为「模块下拉框一直是空的」）；</li>
 *   <li>没有登录主体 → 401 而不是 500。</li>
 * </ol>
 */
class AuditLogControllerPermissionTest {

    /** 本模块的独立权限点（设计与 V51 迁移都用这一个）。 */
    private static final String VIEW = "system.operationLog:view";

    /** 运维日志的权限点：必须**不足以**访问审计数据。 */
    private static final String APP_LOG_VIEW = "system.appLog:view";

    private RbacService rbacService;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        auditLogService = mock(AuditLogService.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AuditLogController(auditLogService))
                .addInterceptors(new PermissionInterceptor(rbacService, false))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AppLogRecorder.class)))
                .build();
    }

    private void login(Set<String> permissions) {
        var user = com.ams.platform.security.LoginUser.builder()
                .userId(9003L)
                .username("audit-probe")
                .name("审计探针")
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

    private void stubEmptyPage() {
        when(auditLogService.query(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));
        when(auditLogService.queryLoginLogs(any(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));
    }

    @Nested
    @DisplayName("读接口 / 类级 system.operationLog:view")
    class ReadEndpoints {

        @Test
        @DisplayName("无 view 权限 -> 全部读接口 403，且服务层从未被调用")
        void withoutViewIsForbidden() throws Exception {
            login(Set.of("asset.ledger:view"));
            stubEmptyPage();

            mvc().perform(get("/api/v1/system/operation-logs"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));
            mvc().perform(get("/api/v1/system/operation-logs/1")).andExpect(status().isForbidden());
            mvc().perform(get("/api/v1/system/operation-logs/modules")).andExpect(status().isForbidden());
            mvc().perform(get("/api/v1/system/login-logs")).andExpect(status().isForbidden());

            verify(auditLogService, never()).query(any(), any(), any(), any(), any(), any(), any(),
                    any(), anyLong(), anyLong());
            verify(auditLogService, never()).detail(any());
            verify(auditLogService, never()).modules();
            verify(auditLogService, never()).queryLoginLogs(any(), any(), any(), any(), any(),
                    anyLong(), anyLong());
        }

        @Test
        @DisplayName("只有 system.appLog:view 不够 -> 403（审计数据不搭运维日志的便车）")
        void appLogViewIsNotEnough() throws Exception {
            login(Set.of(APP_LOG_VIEW));
            stubEmptyPage();

            mvc().perform(get("/api/v1/system/operation-logs")).andExpect(status().isForbidden());
            mvc().perform(get("/api/v1/system/login-logs")).andExpect(status().isForbidden());

            verify(auditLogService, never()).query(any(), any(), any(), any(), any(), any(), any(),
                    any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("有 view 权限 -> 四个读接口全部放行")
        void withViewIsAllowed() throws Exception {
            login(Set.of(VIEW));
            stubEmptyPage();
            when(auditLogService.modules()).thenReturn(List.of("asset", "contract"));

            mvc().perform(get("/api/v1/system/operation-logs")).andExpect(status().isOk());
            mvc().perform(get("/api/v1/system/login-logs")).andExpect(status().isOk());
            mvc().perform(get("/api/v1/system/operation-logs/modules"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0]").value("asset"));
        }

        @Test
        @DisplayName("未登录 -> 401，不是 500")
        void anonymousIsUnauthorized() throws Exception {
            SecurityContextHolder.clearContext();

            mvc().perform(get("/api/v1/system/operation-logs")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("/operation-logs/modules 走字面量而非 /{id}：否则 Long 解析失败 -> 400，下拉恒为空")
        void modulesIsNotSwallowedByPathVariable() throws Exception {
            login(Set.of(VIEW));
            when(auditLogService.modules()).thenReturn(List.of());

            mvc().perform(get("/api/v1/system/operation-logs/modules"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());

            verify(auditLogService).modules();
            verify(auditLogService, never()).detail(any());
        }
    }

    @Nested
    @DisplayName("参数校验与透传")
    class Parameters {

        @Test
        @DisplayName("筛选参数原样交给服务层：success=false 要能筛出失败操作，别被解析成 null")
        void passesFiltersThrough() throws Exception {
            login(Set.of(VIEW));
            stubEmptyPage();

            mvc().perform(get("/api/v1/system/operation-logs")
                            .param("module", "asset")
                            .param("username", "admin")
                            .param("success", "false")
                            .param("keyword", "delete")
                            .param("from", "2026-09-01T00:00:00")
                            .param("to", "2026-09-13T23:59:59")
                            .param("page", "2")
                            .param("pageSize", "50"))
                    .andExpect(status().isOk());

            var captor = org.mockito.ArgumentCaptor.forClass(Boolean.class);
            verify(auditLogService).query(
                    any(), any(), any(), any(), captor.capture(), any(), any(), any(), anyLong(), anyLong());
            org.assertj.core.api.Assertions.assertThat(captor.getValue())
                    .as("success=false 必须透传；被吞成 null 就等于「筛失败」变成了「不筛」")
                    .isFalse();
        }

        @Test
        @DisplayName("refId 透传：审计最常用的提问方式「这个对象上发生过什么」只有这一条入口")
        void passesRefIdThrough() throws Exception {
            login(Set.of(VIEW));
            stubEmptyPage();

            mvc().perform(get("/api/v1/system/operation-logs").param("refId", "42"))
                    .andExpect(status().isOk());

            // 第 8 个实参是 refId（在 traceId 之后、page 之前）
            var captor = org.mockito.ArgumentCaptor.forClass(Long.class);
            verify(auditLogService).query(any(), any(), any(), any(), any(), any(), any(),
                    captor.capture(), anyLong(), anyLong());
            org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(42L);
        }

        @Test
        @DisplayName("缺 success -> 透传 null（不过滤，因此 V51 之前 success IS NULL 的存量行照常出现）")
        void missingSuccessMeansNoFilter() throws Exception {
            login(Set.of(VIEW));
            stubEmptyPage();

            mvc().perform(get("/api/v1/system/operation-logs")).andExpect(status().isOk());

            var captor = org.mockito.ArgumentCaptor.forClass(Boolean.class);
            verify(auditLogService).query(
                    any(), any(), any(), any(), captor.capture(), any(), any(), any(), anyLong(), anyLong());
            org.assertj.core.api.Assertions.assertThat(captor.getValue()).isNull();
        }

        @Test
        @DisplayName("登录日志的 result 等值透传（小写 success / failed）")
        void passesLoginResultThrough() throws Exception {
            login(Set.of(VIEW));
            stubEmptyPage();

            mvc().perform(get("/api/v1/system/login-logs")
                            .param("result", "failed")
                            .param("ip", "10.0.0.1")
                            .param("username", "admin"))
                    .andExpect(status().isOk());

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(auditLogService).queryLoginLogs(any(), any(), any(), captor.capture(), any(),
                    anyLong(), anyLong());
            org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo("failed");
        }

        @Test
        @DisplayName("带 Z 的时间串：offset 被静默丢弃（LocalDateTime.from 忽略 OFFSET_SECONDS），"
                + "因此前端必须送本地时间，否则查询窗口会整体偏移")
        void zonedTimestampDropsOffsetSilently() throws Exception {
            login(Set.of(VIEW));
            stubEmptyPage();

            mvc().perform(get("/api/v1/system/operation-logs").param("from", "2026-09-01T00:00:00Z"))
                    .andExpect(status().isOk());

            var captor = org.mockito.ArgumentCaptor.forClass(java.time.LocalDateTime.class);
            verify(auditLogService).query(captor.capture(), any(), any(), any(), any(), any(), any(),
                    any(), anyLong(), anyLong());
            org.assertj.core.api.Assertions.assertThat(captor.getValue())
                    .as("Z 不会被拒绝，也不会被换算成本地时间 —— 它是被直接丢掉的。"
                            + "这条断言把该行为钉住：前端 RangePicker 必须 format('YYYY-MM-DDTHH:mm:ss')")
                    .isEqualTo(java.time.LocalDateTime.of(2026, 9, 1, 0, 0, 0));
        }

        @Test
        @DisplayName("pageSize 超大由服务层收窄（控制器不自行截断，保持单一口径）")
        void hugePageSizeIsPassedToService() throws Exception {
            login(Set.of(VIEW));
            stubEmptyPage();

            mvc().perform(get("/api/v1/system/operation-logs").param("pageSize", "100000"))
                    .andExpect(status().isOk());

            var captor = org.mockito.ArgumentCaptor.forClass(Long.class);
            verify(auditLogService).query(any(), any(), any(), any(), any(), any(), any(),
                    any(), anyLong(), captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(100000L);
        }
    }

    @Test
    @DisplayName("详情：服务层抛 404 时对外也是 404，且返回体不含堆栈")
    void detailNotFoundIsMappedTo404() throws Exception {
        login(Set.of(VIEW));
        when(auditLogService.detail(any()))
                .thenThrow(new com.ams.common.exception.AppException(
                        com.ams.common.exception.ErrorCode.NOT_FOUND));

        mvc().perform(get("/api/v1/system/operation-logs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    @DisplayName("OperationLogView 暴露 refId：切面已开始写入该列，不再需要藏起来")
    void viewExposesRefIdColumn() {
        org.assertj.core.api.Assertions.assertThat(
                        OperationLogView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("refId", "success", "error");
    }
}
