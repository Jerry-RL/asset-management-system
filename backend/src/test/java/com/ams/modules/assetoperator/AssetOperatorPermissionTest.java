package com.ams.modules.assetoperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.common.web.PageResult;
import com.ams.modules.assetoperator.controller.AssetOperatorController;
import com.ams.modules.assetoperator.dto.AssetOperatorRoleOption;
import com.ams.modules.assetoperator.dto.AssetOperatorScopeOption;
import com.ams.modules.assetoperator.dto.AssetOperatorUserOption;
import com.ams.modules.assetoperator.dto.AssetOperatorView;
import com.ams.modules.assetoperator.service.AssetOperatorService;
import com.ams.platform.observability.service.AppLogRecorder;
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
 * 资产运营人员管理的权限闭环（V60）。
 *
 * <p>关键的四条：
 * <ol>
 *   <li><b>三个下拉只要求本模块的 {@code :view}</b> —— 人员 / 角色 / 范围分别对应
 *       {@code org.user:view} / {@code system.role:view} / {@code asset.ledger:view}
 *       三个**别的**菜单权限；若下拉复用了那些端点，缺任一项权限的人就选不出东西，
 *       功能等于不存在。这里用「只给 {@code ops.assetOperator:view}」正面钉住这一点；</li>
 *   <li>删除权与编辑权分开（{@code :update} 不得删）；</li>
 *   <li>新增只认 {@code :create}（{@code :update} 不得新增）；</li>
 *   <li>每个 403 用例都断言**服务未被调用** —— 403 若发生在副作用之后，拦截就没有意义。</li>
 * </ol>
 */
class AssetOperatorPermissionTest {

    private static final long OPERATOR_ID = 66L;

    private RbacService rbacService;
    private AssetOperatorService service;
    private AssetOperatorController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        service = mock(AssetOperatorService.class);
        when(service.get(any())).thenReturn(new AssetOperatorView());
        when(service.create(any())).thenReturn(new AssetOperatorView());
        when(service.update(any(), any())).thenReturn(new AssetOperatorView());
        when(service.updateStatus(any(), any())).thenReturn(new AssetOperatorView());
        when(service.page(anyLong(), anyLong(), any(), any()))
                .thenReturn(PageResult.of(List.of(), 0, 1, 10));
        when(service.userOptions(any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(PageResult.of(List.<AssetOperatorUserOption>of(), 0, 1, 50));
        when(service.roleOptions()).thenReturn(List.<AssetOperatorRoleOption>of());
        when(service.scopeOptions(any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(PageResult.of(List.<AssetOperatorScopeOption>of(), 0, 1, 50));
        controller = new AssetOperatorController(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("列表与详情：缺 :view 一律 403，且服务不被调用")
    void readsRequireView() throws Exception {
        login(Set.of());

        mvc().perform(get("/api/v1/asset-operators")).andExpect(status().isForbidden());
        mvc().perform(get("/api/v1/asset-operators/{id}", OPERATOR_ID))
                .andExpect(status().isForbidden());

        verify(service, never()).page(anyLong(), anyLong(), any(), any());
        verify(service, never()).get(any());
    }

    @Test
    @DisplayName("三个下拉只要求本模块 :view —— 不要求 org.user / system.role / asset.ledger")
    void optionEndpointsOnlyNeedOwnViewPermission() throws Exception {
        login(Set.of("ops.assetOperator:view"));

        mvc().perform(get("/api/v1/asset-operators/user-options")).andExpect(status().isOk());
        mvc().perform(get("/api/v1/asset-operators/role-options")).andExpect(status().isOk());
        mvc().perform(get("/api/v1/asset-operators/scope-options")
                        .param("scopeType", "project")
                        .param("companyId", "2"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("下拉端点不能被 /{id} 抢匹配（否则「user-options」转不成 Long 会 500）")
    void optionEndpointsAreNotSwallowedById() throws Exception {
        login(Set.of("ops.assetOperator:view"));

        mvc().perform(get("/api/v1/asset-operators/user-options")).andExpect(status().isOk());
        mvc().perform(get("/api/v1/asset-operators/role-options")).andExpect(status().isOk());
        mvc().perform(get("/api/v1/asset-operators/scope-options")
                        .param("scopeType", "asset")
                        .param("companyId", "2"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("新增：只有 :update 必须被拒 —— 改稿权不等于登记权")
    void createNeedsCreatePermission() throws Exception {
        login(Set.of("ops.assetOperator:view", "ops.assetOperator:update"));

        mvc().perform(post("/api/v1/asset-operators")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2,\"roleIds\":[1],\"scopes\":[{\"scopeType\":\"project\",\"scopeId\":3}]}"))
                .andExpect(status().isForbidden());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("新增：授予 :create -> 放行")
    void createWithPermissionSucceeds() throws Exception {
        login(Set.of("ops.assetOperator:create"));

        mvc().perform(post("/api/v1/asset-operators")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2,\"roleIds\":[1],\"scopes\":[{\"scopeType\":\"project\",\"scopeId\":3}]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("编辑与停用：需 :update，只有 :create 被拒")
    void updateNeedsUpdatePermission() throws Exception {
        login(Set.of("ops.assetOperator:create"));

        mvc().perform(put("/api/v1/asset-operators/{id}", OPERATOR_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc().perform(put("/api/v1/asset-operators/{id}/status", OPERATOR_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(status().isForbidden());

        verify(service, never()).update(any(), any());
        verify(service, never()).updateStatus(any(), any());
    }

    @Test
    @DisplayName("删除：需 :delete，只有 :update 被拒")
    void deleteNeedsDeletePermission() throws Exception {
        login(Set.of("ops.assetOperator:update"));

        mvc().perform(delete("/api/v1/asset-operators/{id}", OPERATOR_ID))
                .andExpect(status().isForbidden());

        verify(service, never()).delete(any());
    }

    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9311L)
                .username("asset-operator-probe")
                .name("资产运营人员权限探针")
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

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(rbacService, false))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AppLogRecorder.class)))
                .build();
    }
}
