package com.ams.modules.disposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.platform.observability.service.AppLogRecorder;
import com.ams.modules.asset.controller.AssetController;
import com.ams.modules.asset.service.AssetDossierService;
import com.ams.modules.asset.service.AssetQrService;
import com.ams.modules.asset.service.AssetService;
import com.ams.modules.asset.service.AssetStructureService;
import com.ams.modules.asset.service.ProjectZoneFloorService;
import com.ams.modules.disposal.dto.DisposalOrderInput;
import com.ams.modules.disposal.service.DisposalService;
import com.ams.modules.record.dto.DisposalOrderView;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.PermissionInterceptor;
import com.ams.platform.security.RbacService;
import com.ams.support.RbacFixtures;
import java.util.ArrayList;
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
 * 资产处置单同步端点的权限闭环（{@code PUT /assets/{assetId}/disposals}）。
 *
 * <p>权限码是 {@code operation.disposal:create}（登记处置），**不是** {@code asset.ledger:update}：
 * 面板上的录取入口与流程推进是两件事，与 {@code POST /disposals} 同一口径。
 * 因此这里必须证明两件事，缺一条这个端点就等于「能改资产 = 能登记处置」：
 *
 * <ol>
 *   <li>只有 {@code asset.ledger:update} 的账号被拒（403），且服务从未被调用；</li>
 *   <li>持有 {@code operation.disposal:create} 的账号放行。</li>
 * </ol>
 *
 * <p>注释与断言都对着控制器方法上的 {@code @RequiresPerm} —— 注解被删或动作被改都会让用例失败。
 */
class AssetDisposalSyncPermissionTest {

    private static final long ASSET_ID = 7L;
    /** 归属解析出的公司；被测账号 dataScope=all 且无排除，因此可通过归属校验。 */
    private static final long VISIBLE_COMPANY = 2L;

    private RbacService rbacService;
    private DisposalService disposalService;
    private AssetController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        disposalService = mock(DisposalService.class);
        when(disposalService.syncForAsset(any(), anyList())).thenReturn(new ArrayList<DisposalOrderView>());
        OwnershipResolver ownershipResolver = mock(OwnershipResolver.class);
        when(ownershipResolver.ofAsset(any())).thenReturn(VISIBLE_COMPANY);
        controller = new AssetController(mock(AssetService.class),
                mock(AssetStructureService.class), mock(AssetDossierService.class),
                mock(AssetQrService.class), ownershipResolver, rbacService, disposalService,
                mock(ProjectZoneFloorService.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("同步处置单 / 只有 asset.ledger:update（能改资产）-> 403，服务未被调用")
    void syncNeedsDisposalPermissionNotLedgerPermission() throws Exception {
        login(Set.of("asset.ledger:update"));

        mvc().perform(put("/api/v1/assets/{assetId}/disposals", ASSET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[{\"disposalType\":\"sale\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verify(disposalService, never()).syncForAsset(any(), anyList());
    }

    @Test
    @DisplayName("同步处置单 / 授予 operation.disposal:create -> 放行，请求体绑定到服务")
    @SuppressWarnings("unchecked")
    void syncWithPermissionSucceeds() throws Exception {
        login(Set.of("operation.disposal:create"));

        mvc().perform(put("/api/v1/assets/{assetId}/disposals", ASSET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[{\"disposalType\":\"sale\",\"id\":11},{\"id\":null}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 用 anyList() 桩、再用 captor 断言内容：只 verify 调用无法发现「请求体压根没绑定」
        ArgumentCaptor<List<DisposalOrderInput>> captor = ArgumentCaptor.forClass(List.class);
        verify(disposalService).syncForAsset(eq(ASSET_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getId()).isEqualTo(11L);
        assertThat(captor.getValue().get(0).getDisposalType()).isEqualTo("sale");
        // 无 id = 新增：服务端据此建成草稿
        assertThat(captor.getValue().get(1).getId()).isNull();
    }

    /** 以非 super_admin 身份登录：roles 不含 super_admin，否则拦截器直接放行、用例空转。 */
    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9003L)
                .username("disposal-sync-probe")
                .name("处置同步权限探针")
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
                .setControllerAdvice(new GlobalExceptionHandler(mock(AppLogRecorder.class)))
                .build();
    }
}
