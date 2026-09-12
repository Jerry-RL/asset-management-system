package com.ams.modules.record.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ams.common.exception.GlobalExceptionHandler;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.service.OwnerResolver;
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
 * record-sheet 六个端点的权限闭环（设计 §5.2 / §5.3，验收第 8 条）。
 *
 * <p>结构与 {@code AssetZoneEndpointPermissionTest} 一致：真实控制器 + 真实拦截器，
 * 权限码由 {@link PermissionInterceptor} 从 {@code @RequiresPerm} 本身解析 ——
 * 因此「注解被删」或「动作写错」都会让用例失败，而不是让 403 断言因为账号本来就没权限而空转。
 *
 * <p>每个用例**同时**跑两个方向：无权 → 403 且服务未被调用；有权 → 200。
 * 只测 403 无法证明权限码写对了（一个拼错的码对所有人都是 403，测试照样全绿）。
 */
class RecordSheetEndpointPermissionTest {

    private static final long ASSET_ID = 7L;
    private static final long PROJECT_ID = 1L;
    private static final long ZONE_ID = 9L;

    private static final String ASSET_PATH = "/api/v1/assets/{id}/record-sheet";
    private static final String PROJECT_PATH = "/api/v1/projects/{id}/record-sheet";
    private static final String ZONE_PATH = "/api/v1/projects/{pid}/zones/{zoneId}/record-sheet";

    private RbacService rbacService;
    private RecordSheetService service;
    private RecordSheetController controller;

    @BeforeEach
    void setUp() {
        rbacService = RbacFixtures.standard().newService(1L);
        service = mock(RecordSheetService.class);
        when(service.read(any(), any())).thenReturn(new RecordSheetView());
        when(service.save(any(), any(), any())).thenReturn(new RecordSheetView());
        // OwnerResolver 用桩：数据范围断言由 OwnerResolverTest 单独覆盖，这里只验权限码
        controller = new RecordSheetController(service, mock(OwnerResolver.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("资产 record-sheet：读需 asset.ledger:view，写需 asset.ledger:update")
    void assetSheetPermissions() throws Exception {
        login(Set.of());
        mvc().perform(get(ASSET_PATH, ASSET_ID)).andExpect(status().isForbidden());
        mvc().perform(put(ASSET_PATH, ASSET_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(service, never()).read(any(), any());
        verify(service, never()).save(any(), any(), any());

        login(Set.of("asset.ledger:view"));
        mvc().perform(get(ASSET_PATH, ASSET_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(service).read(RecordOwnerType.ASSET, ASSET_ID);

        login(Set.of("asset.ledger:update"));
        mvc().perform(put(ASSET_PATH, ASSET_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).save(any(RecordOwnerType.class), any(), any(RecordSheetRequest.class));
    }

    @Test
    @DisplayName("项目 record-sheet：读需 asset.project:view，写需 asset.project:update")
    void projectSheetPermissions() throws Exception {
        login(Set.of());
        mvc().perform(get(PROJECT_PATH, PROJECT_ID)).andExpect(status().isForbidden());
        mvc().perform(put(PROJECT_PATH, PROJECT_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        login(Set.of("asset.project:view"));
        mvc().perform(get(PROJECT_PATH, PROJECT_ID)).andExpect(status().isOk());
        verify(service).read(RecordOwnerType.PROJECT, PROJECT_ID);

        login(Set.of("asset.project:update"));
        mvc().perform(put(PROJECT_PATH, PROJECT_ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("分区 record-sheet：复用 asset.project 权限码，不引入新菜单")
    void zoneSheetPermissions() throws Exception {
        login(Set.of());
        mvc().perform(get(ZONE_PATH, PROJECT_ID, ZONE_ID)).andExpect(status().isForbidden());
        mvc().perform(put(ZONE_PATH, PROJECT_ID, ZONE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        login(Set.of("asset.project:view"));
        mvc().perform(get(ZONE_PATH, PROJECT_ID, ZONE_ID)).andExpect(status().isOk());
        verify(service).read(RecordOwnerType.ZONE, ZONE_ID);

        login(Set.of("asset.project:update"));
        mvc().perform(put(ZONE_PATH, PROJECT_ID, ZONE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).save(RecordOwnerType.ZONE, ZONE_ID, any(RecordSheetRequest.class));
    }

    /** 以非 super_admin 身份登录：roles 不含 super_admin，否则拦截器直接放行、用例空转。 */
    private void login(Set<String> permissions) {
        LoginUser user = LoginUser.builder()
                .userId(9001L)
                .username("record-probe")
                .name("记录权限探针")
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
