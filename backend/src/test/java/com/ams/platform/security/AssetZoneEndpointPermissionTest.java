package com.ams.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.modules.asset.controller.AssetController;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.service.AssetDossierService;
import com.ams.modules.asset.service.AssetQrService;
import com.ams.modules.asset.service.AssetService;
import com.ams.modules.asset.service.AssetStructureService;
import com.ams.support.RbacFixtures;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 项目列表展开行内的分区接口权限闭环（设计 §4.1、验收第 6 条）。
 *
 * <p>结构与 {@link WriteEndpointPermissionTest} 一致：真实控制器 + 真实拦截器，
 * 权限码由 {@link PermissionInterceptor} 从控制器方法上的 {@code @RequiresPerm} 本身解析，
 * 因此「注解被删」或「动作被改」都会让用例失败，而不是让 403 断言因为账号本来就没权限而空转。
 *
 * <p>设计口径：分区是项目配置的一部分，三个操作统一复用 {@code asset.project:update}。
 */
class AssetZoneEndpointPermissionTest {

    private static final long PROJECT_ID = 7L;
    private static final long ZONE_ID = 9L;
    /** 归属解析出的公司；被测账号 dataScope=all 且无排除，因此不受限、可通过归属校验。 */
    private static final long VISIBLE_COMPANY = 2L;

    private RbacService rbacService;
    private AssetService assetService;
    private AssetController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        assetService = mock(AssetService.class);
        OwnershipResolver ownershipResolver = mock(OwnershipResolver.class);
        when(ownershipResolver.ofProject(any())).thenReturn(VISIBLE_COMPANY);
        when(assetService.createProjectZone(any(), any())).thenReturn(new ProjectZone());
        when(assetService.updateProjectZone(any(), any(), any())).thenReturn(new ProjectZone());
        controller = new AssetController(assetService, mock(AssetStructureService.class),
                mock(AssetDossierService.class), mock(AssetQrService.class), ownershipResolver,
                rbacService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("新增分区 / 未授予 asset.project:update -> 403，且服务从未被调用")
    void createZoneWithoutPermissionIsForbidden() throws Exception {
        login(Set.of());

        mvc().perform(post("/api/v1/projects/{id}/zones", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A区\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(assetService, never()).createProjectZone(any(), any());
    }

    @Test
    @DisplayName("新增分区 / 授予 asset.project:update -> 成功")
    void createZoneWithPermissionSucceeds() throws Exception {
        login(Set.of("asset.project:update"));

        mvc().perform(post("/api/v1/projects/{id}/zones", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A区\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<ProjectZone> captor = ArgumentCaptor.forClass(ProjectZone.class);
        verify(assetService).createProjectZone(eq(PROJECT_ID), captor.capture());
        // 断言请求体真的被绑定并透传到服务：any() 会在 @RequestBody 被去掉/绑错时照样通过
        assertThat(captor.getValue().getName()).isEqualTo("A区");
        // 归属必须来自 URL 而非请求体（用例未在 body 里给 projectId，故应为 null）
        assertThat(captor.getValue().getProjectId()).isNull();
    }

    @Test
    @DisplayName("编辑分区 / 未授予 asset.project:update -> 403，且服务从未被调用")
    void updateZoneWithoutPermissionIsForbidden() throws Exception {
        login(Set.of());

        mvc().perform(put("/api/v1/projects/{id}/zones/{zoneId}", PROJECT_ID, ZONE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A区\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(assetService, never()).updateProjectZone(any(), any(), any());
    }

    @Test
    @DisplayName("编辑分区 / 授予 asset.project:update -> 成功")
    void updateZoneWithPermissionSucceeds() throws Exception {
        login(Set.of("asset.project:update"));

        mvc().perform(put("/api/v1/projects/{id}/zones/{zoneId}", PROJECT_ID, ZONE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A区\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<ProjectZone> captor = ArgumentCaptor.forClass(ProjectZone.class);
        verify(assetService).updateProjectZone(eq(PROJECT_ID), eq(ZONE_ID), captor.capture());
        // 同上：证明路径参数与请求体分别正确落位
        assertThat(captor.getValue().getName()).isEqualTo("A区");
        assertThat(captor.getValue().getProjectId()).isNull();
    }

    @Test
    @DisplayName("删除分区 / 未授予 asset.project:update -> 403，且服务从未被调用")
    void deleteZoneWithoutPermissionIsForbidden() throws Exception {
        login(Set.of());

        mvc().perform(delete("/api/v1/projects/{id}/zones/{zoneId}", PROJECT_ID, ZONE_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(assetService, never()).deleteProjectZone(any(), any());
    }

    @Test
    @DisplayName("删除分区 / 授予 asset.project:update -> 成功")
    void deleteZoneWithPermissionSucceeds() throws Exception {
        login(Set.of("asset.project:update"));

        mvc().perform(delete("/api/v1/projects/{id}/zones/{zoneId}", PROJECT_ID, ZONE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(assetService).deleteProjectZone(PROJECT_ID, ZONE_ID);
    }

    /** 以非 super_admin 身份登录：roles 不含 super_admin，否则拦截器直接放行、用例空转。 */
    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9001L)
                .username("zone-probe")
                .name("分区权限探针")
                .companyId(VISIBLE_COMPANY)
                .homeCompanyId(VISIBLE_COMPANY)
                .departmentId(1L)
                .clientType("admin")
                .roles(Set.of("asset_mgr"))
                .permissions(permissions)
                .dataScope("all")
                .companyScoped(false)
                .excludedCompanyIds(Set.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    /** 真实控制器 + 真实拦截器 + 真实异常处理（把 AppException 翻成 HTTP 403 + code 40300）。 */
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(rbacService, false))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
