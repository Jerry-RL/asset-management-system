package com.ams.modules.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.record.RecordOwnerType;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 宿主解析与数据范围断言（设计 §5.3）。
 *
 * <p>三条最容易漏的语义：
 * <ol>
 *   <li>宿主不存在 → 404，而不是「归属公司为 null 于是按无权处理」——后者会让
 *       「对象不存在」和「无权限」在响应上无法区分，排查困难且泄露信息；</li>
 *   <li>分区必须经 <b>项目</b> 取公司，而不是自己有一列公司；</li>
 *   <li>软删的分区不算存在（否则删掉的分区还能继续挂记录）。</li>
 * </ol>
 */
class OwnerResolverTest {

    private static final Long ZONE_ID = 9L;
    private static final Long PROJECT_ID = 1L;
    private static final Long COMPANY_ID = 2L;

    private final AssetMapper assetMapper = mock(AssetMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ProjectZoneMapper projectZoneMapper = mock(ProjectZoneMapper.class);
    private final OwnershipResolver ownershipResolver = mock(OwnershipResolver.class);
    private final RbacService rbacService = mock(RbacService.class);

    private final OwnerResolver resolver =
            new OwnerResolver(assetMapper, projectMapper, projectZoneMapper, ownershipResolver, rbacService);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("分区经项目取公司，不做自己的公司列")
    void zoneResolvesCompanyThroughProject() {
        ProjectZone zone = new ProjectZone();
        zone.setId(ZONE_ID);
        zone.setProjectId(PROJECT_ID);
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(zone);
        when(ownershipResolver.ofProject(PROJECT_ID)).thenReturn(COMPANY_ID);
        login();

        assertThat(resolver.assertAccessible(RecordOwnerType.ZONE, ZONE_ID)).isEqualTo(COMPANY_ID);

        verify(rbacService).assertCompanyAccess(any(LoginUser.class), eq(COMPANY_ID));
    }

    @Test
    @DisplayName("分区不存在（含已软删）→ 404，且不做数据范围断言")
    void missingZoneIsNotFound() {
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.ZONE, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("项目不存在 → 404")
    void missingProjectIsNotFound() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.PROJECT, PROJECT_ID))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("ownerId 缺失 → 400，不落成一次全表查询")
    void nullOwnerIdIsBadRequest() {
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.ASSET, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("归属对象");
    }

    @Test
    @DisplayName("资产存在但归属推导不出公司 → 返回 null，仍交给 assertCompanyAccess 判定")
    void assetWithoutCompanyStillGoesThroughScopeCheck() {
        when(assetMapper.selectById(7L)).thenReturn(new com.ams.modules.asset.entity.Asset());
        when(ownershipResolver.ofAsset(7L)).thenReturn(null);
        login();

        assertThat(resolver.assertAccessible(RecordOwnerType.ASSET, 7L)).isNull();

        verify(rbacService).assertCompanyAccess(any(LoginUser.class), eq(null));
    }

    /** 用真实 SecurityContext 而不是桩 SecurityUtils（它是静态入口，桩不住）。 */
    private void login() {
        LoginUser user = LoginUser.builder()
                .userId(9001L)
                .username("record-probe")
                .name("记录权限探针")
                .roles(Set.of("asset_mgr"))
                .permissions(Set.of())
                .dataScope("all")
                .excludedCompanyIds(Set.of())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
