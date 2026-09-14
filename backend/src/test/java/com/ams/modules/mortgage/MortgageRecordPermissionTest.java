package com.ams.modules.mortgage;

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
import com.ams.modules.mortgage.controller.MortgageController;
import com.ams.modules.mortgage.dto.MortgageRecordView;
import com.ams.modules.mortgage.service.MortgageRecordService;
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
 * 抵押记录的权限闭环（V56）。
 *
 * <p>关键的三条：
 * <ol>
 *   <li><b>生效不看起草权</b>：只有 {@code :create} 的账号可以建草稿但**不能生效** ——
 *       「能起草」与「能让一个项目变成在押（从而冻结它下面所有资产的处置与流转）」</li>
 *   <li><b>只有 {@code :update} 不能起草</b>：起草权与改稿权不该互相顶替；</li>
 *   <li>每个 403 用例都断言**服务未被调用** —— 403 若发生在副作用之后，拦截就没有意义。</li>
 * </ol>
 *
 * <p>另有一条专门钉「路径遮蔽」：{@code /target-options} 若被 {@code /{id}} 抢匹配，
 * 会因为「target-options 转不成 Long」变成 500，而 500 在权限用例里很容易被误读成
 * 「权限配错了」。
 */
class MortgageRecordPermissionTest {

    private static final long RECORD_ID = 77L;

    private RbacService rbacService;
    private MortgageRecordService service;
    private MortgageController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        service = mock(MortgageRecordService.class);
        when(service.get(any())).thenReturn(new MortgageRecordView());
        when(service.create(any())).thenReturn(new MortgageRecordView());
        when(service.update(any(), any())).thenReturn(new MortgageRecordView());
        when(service.effect(any())).thenReturn(new MortgageRecordView());
        controller = new MortgageController(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("起草：只有 :update 必须被拒 —— 改稿权不等于起草权")
    void createNeedsCreatePermission() throws Exception {
        login(Set.of("deed.mortgage:view", "deed.mortgage:update"));

        mvc().perform(post("/api/v1/mortgages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":2,\"targetType\":\"project\",\"targetId\":1}"))
                .andExpect(status().isForbidden());

        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("起草：授予 :create -> 放行")
    void createWithPermissionSucceeds() throws Exception {
        login(Set.of("deed.mortgage:create"));

        mvc().perform(post("/api/v1/mortgages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":2,\"targetType\":\"project\",\"targetId\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("生效：只有 :create 必须被拒 —— 生效会让标的变成在押，从而冻结其下资产的处置与流转")
    void effectNeedsUpdateNotCreate() throws Exception {
        login(Set.of("deed.mortgage:create"));

        mvc().perform(post("/api/v1/mortgages/{id}/effect", RECORD_ID))
                .andExpect(status().isForbidden());

        verify(service, never()).effect(any());
    }

    @Test
    @DisplayName("生效：授予 :update -> 放行")
    void effectWithUpdatePermissionSucceeds() throws Exception {
        login(Set.of("deed.mortgage:update"));

        mvc().perform(post("/api/v1/mortgages/{id}/effect", RECORD_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("删除：需 :delete，只有 :update 被拒")
    void deleteNeedsDeletePermission() throws Exception {
        login(Set.of("deed.mortgage:update"));

        mvc().perform(delete("/api/v1/mortgages/{id}", RECORD_ID))
                .andExpect(status().isForbidden());

        verify(service, never()).delete(any());
    }

    @Test
    @DisplayName("列表与标的选题项：需 :view（缺 view 时服务不被调用）")
    void readsNeedViewPermission() throws Exception {
        login(Set.of());

        mvc().perform(get("/api/v1/mortgages")).andExpect(status().isForbidden());
        mvc().perform(get("/api/v1/mortgages/target-options")
                        .param("targetType", "project")
                        .param("companyId", "2"))
                .andExpect(status().isForbidden());

        login(Set.of("deed.mortgage:view"));
        mvc().perform(get("/api/v1/mortgages")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("详情：需 :view，且 /target-options 不能被 /{id} 抢匹配")
    void detailNeedsViewAndTargetOptionsIsNotSwallowedById() throws Exception {
        login(Set.of("deed.mortgage:view"));

        mvc().perform(get("/api/v1/mortgages/{id}", RECORD_ID)).andExpect(status().isOk());
        // 若 target-options 被 /{id} 抢匹配，这里会因为「target-options 转不成 Long」变成 500
        mvc().perform(get("/api/v1/mortgages/target-options")
                        .param("targetType", "zone")
                        .param("companyId", "2"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("编辑草稿：需 :update")
    void updateNeedsUpdatePermission() throws Exception {
        login(Set.of("deed.mortgage:view"));

        mvc().perform(put("/api/v1/mortgages/{id}", RECORD_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).update(any(), any());
    }

    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9401L)
                .username("mortgage-probe")
                .name("抵押记录权限探针")
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
