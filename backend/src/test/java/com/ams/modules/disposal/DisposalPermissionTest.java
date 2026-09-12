package com.ams.modules.disposal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.modules.disposal.controller.DisposalController;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.service.DisposalService;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.PermissionInterceptor;
import com.ams.platform.security.RbacService;
import com.ams.support.RbacFixtures;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 处置流转的权限闭环（设计 §5.3 职责分离，验收第 3 条）。
 *
 * <p>本用例存在的意义：`DisposalController` 原本**整类没有** `@RequiresPerm`，
 * 任何登录用户都能审批处置。加上注解后必须证明「审批用独立权限码」——
 * 否则「能编辑资产的人」就等于「能自提自批」。
 *
 * <p>关键的一条是 {@link #approveNeedsDedicatedPermission()}：持有
 * {@code operation.disposal:update} 但**没有** {@code approve} 的账号必须被拒。
 * 只有这一条能证明审批没有被「写权限」顺带放行。
 */
class DisposalPermissionTest {

    private static final long ORDER_ID = 55L;

    private RbacService rbacService;
    private DisposalService disposalService;
    private DisposalController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        disposalService = mock(DisposalService.class);
        when(disposalService.create(any())).thenReturn(new DisposalOrder());
        when(disposalService.submit(any())).thenReturn(new DisposalOrder());
        when(disposalService.execute(any(), any(), any())).thenReturn(new DisposalOrder());
        when(disposalService.complete(any())).thenReturn(new DisposalOrder());
        controller = new DisposalController(disposalService, mock(RecordSheetService.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("新建处置单：未授予 operation.disposal:create -> 403，服务未被调用")
    void createNeedsCreatePermission() throws Exception {
        login(Set.of());

        mvc().perform(post("/api/v1/disposals")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":7}"))
                .andExpect(status().isForbidden());

        verify(disposalService, never()).create(any());
    }

    @Test
    @DisplayName("审批：只有 operation.disposal:update（写权限）必须被拒 —— 审批不看写权限")
    void approveNeedsDedicatedPermission() throws Exception {
        login(Set.of("operation.disposal:create", "operation.disposal:update"));

        mvc().perform(post("/api/v1/disposals/{id}/approve", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("审批：授予 operation.disposal:approve -> 放行")
    void approveWithPermissionSucceeds() throws Exception {
        login(Set.of("operation.disposal:approve"));

        mvc().perform(post("/api/v1/disposals/{id}/approve", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("执行 / 完成：需 operation.disposal:update")
    void executeAndCompleteNeedUpdatePermission() throws Exception {
        login(Set.of());

        mvc().perform(post("/api/v1/disposals/{id}/execute", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualAmount\":1}"))
                .andExpect(status().isForbidden());
        mvc().perform(post("/api/v1/disposals/{id}/complete", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        login(Set.of("operation.disposal:update"));
        mvc().perform(post("/api/v1/disposals/{id}/execute", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualAmount\":1}"))
                .andExpect(status().isOk());
        mvc().perform(post("/api/v1/disposals/{id}/complete", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9002L)
                .username("disposal-probe")
                .name("处置权限探针")
                .companyId(2L)
                .homeCompanyId(2L)
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

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(rbacService, false))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
