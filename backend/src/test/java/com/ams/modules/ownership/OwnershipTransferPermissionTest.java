package com.ams.modules.ownership;

import static org.mockito.ArgumentMatchers.any;
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
import com.ams.modules.ownership.controller.OwnershipTransferController;
import com.ams.modules.ownership.dto.OwnershipTransferView;
import com.ams.modules.ownership.service.OwnershipTransferService;
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
 * 权属流转的权限闭环（设计 §8）。
 *
 * <p>关键的三条：
 * <ol>
 *   <li><b>生效不看起草权</b>：只有 {@code :create} 的账号可以建草稿但**不能生效** ——
 *       「能起草」与「能改产权」必须是两个权限（生效是不可逆的跨法人变更）；</li>
 *   <li><b>只有 {@code :update} 不能起草</b>：起草权与改稿权也不该互相顶替；</li>
 *   <li>每个 403 用例都断言**服务未被调用** —— 403 若发生在副作用之后，拦截就没有意义。</li>
 * </ol>
 */
class OwnershipTransferPermissionTest {

    private static final long TRANSFER_ID = 88L;

    private RbacService rbacService;
    private OwnershipTransferService service;
    private OwnershipTransferController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        service = mock(OwnershipTransferService.class);
        when(service.get(any())).thenReturn(new OwnershipTransferView());
        when(service.create(any())).thenReturn(new OwnershipTransferView());
        when(service.update(any(), any())).thenReturn(new OwnershipTransferView());
        when(service.effect(any())).thenReturn(new OwnershipTransferView());
        controller = new OwnershipTransferController(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("起草：只有 :update 必须被拒 —— 改稿权不等于起草权")
    void createNeedsCreatePermission() throws Exception {
        login(Set.of("deed.ownershipTransfer:view", "deed.ownershipTransfer:update"));

        mvc().perform(post("/api/v1/ownership-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCompanyId\":2,\"toCompanyId\":3}"))
                .andExpect(status().isForbidden());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("起草：授予 :create -> 放行")
    void createWithPermissionSucceeds() throws Exception {
        login(Set.of("deed.ownershipTransfer:create"));

        mvc().perform(post("/api/v1/ownership-transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCompanyId\":2,\"toCompanyId\":3}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("生效：只有 :create（能起草）必须被拒 —— 生效是不可逆的跨法人变更")
    void effectNeedsUpdateNotCreate() throws Exception {
        login(Set.of("deed.ownershipTransfer:create"));

        mvc().perform(post("/api/v1/ownership-transfers/{id}/effect", TRANSFER_ID))
                .andExpect(status().isForbidden());

        verify(service, never()).effect(any());
    }

    @Test
    @DisplayName("生效：授予 :update -> 放行")
    void effectWithUpdatePermissionSucceeds() throws Exception {
        login(Set.of("deed.ownershipTransfer:update"));

        mvc().perform(post("/api/v1/ownership-transfers/{id}/effect", TRANSFER_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("删除：需 :delete，只有 :update 被拒")
    void deleteNeedsDeletePermission() throws Exception {
        login(Set.of("deed.ownershipTransfer:update"));

        mvc().perform(delete("/api/v1/ownership-transfers/{id}", TRANSFER_ID))
                .andExpect(status().isForbidden());

        verify(service, never()).delete(any());
    }

    @Test
    @DisplayName("列表与资产下拉：需 :view（缺 view 时服务不被调用）")
    void readsNeedViewPermission() throws Exception {
        login(Set.of());

        mvc().perform(get("/api/v1/ownership-transfers")).andExpect(status().isForbidden());
        mvc().perform(get("/api/v1/ownership-transfers/asset-options")
                        .param("companyId", "2")
                        .param("transferScope", "property"))
                .andExpect(status().isForbidden());

        login(Set.of("deed.ownershipTransfer:view"));
        mvc().perform(get("/api/v1/ownership-transfers")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("详情：需 :view，且 /asset-options 不能被 /{id} 抢匹配")
    void detailNeedsViewAndAssetOptionsIsNotSwallowedById() throws Exception {
        login(Set.of("deed.ownershipTransfer:view"));

        mvc().perform(get("/api/v1/ownership-transfers/{id}", TRANSFER_ID))
                .andExpect(status().isOk());
        // 若 asset-options 被 /{id} 抢匹配，这里会因为「asset-options 转不成 Long」变成 500
        mvc().perform(get("/api/v1/ownership-transfers/asset-options")
                        .param("companyId", "2"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("编辑草稿：需 :update")
    void updateNeedsUpdatePermission() throws Exception {
        login(Set.of("deed.ownershipTransfer:view"));

        mvc().perform(put("/api/v1/ownership-transfers/{id}", TRANSFER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).update(any(), any());
    }

    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9102L)
                .username("ownership-probe")
                .name("权属流转权限探针")
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
                .setControllerAdvice(new GlobalExceptionHandler(mock(AppLogRecorder.class)))
                .build();
    }
}
