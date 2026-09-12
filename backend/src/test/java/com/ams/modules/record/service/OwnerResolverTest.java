package com.ams.modules.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.asset.mapper.ProjectZoneMapper;
import com.ams.modules.record.RecordOwnerType;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
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
 * <p>四条最容易漏的语义：
 * <ol>
 *   <li>宿主不存在 → 404，而不是「归属公司为 null 于是按无权处理」——后者会让
 *       「对象不存在」和「无权限」在响应上无法区分，排查困难且泄露信息；</li>
 *   <li>分区必须经 <b>项目</b> 取公司，而不是自己有一列公司；</li>
 *   <li>三种主体一律「未软删才算存在」：软删的宿主不解析归属、也不给挂记录；</li>
 *   <li><b>对象级越权与不存在必须完全不可区分</b>（同 code、同文案，设计 §5.3 第 5 条）——
 *       这条只断言「抛了异常」是测不出来的，必须比对两条路径的 code 与 message。</li>
 * </ol>
 *
 * <p>错误码断言一律用 {@code errorCode} 而不是 {@code hasMessageContaining}：
 * {@code NOT_FOUND} 的文案同样含「归属对象」等字样，只断文案的用例连 400/404 之差都测不出来。
 *
 * <p>Mockito 读不出 {@code LambdaQueryWrapper} 里的 where 条件（见 {@code RbacFixtures} 同类说明），
 * 因此「软删」用例以 {@code selectOne} 返回 {@code null} 代表「该行被 {@code deleted_at IS NULL}
 * 过滤掉」；过滤器本身由实现层静态核对与 CI 覆盖。
 */
class OwnerResolverTest {

    private static final Long ZONE_ID = 9L;
    private static final Long PROJECT_ID = 1L;
    private static final Long ASSET_ID = 7L;
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
        when(rbacService.canAccessCompany(any(LoginUser.class), eq(COMPANY_ID))).thenReturn(true);
        login();

        assertThat(resolver.assertAccessible(RecordOwnerType.ZONE, ZONE_ID)).isEqualTo(COMPANY_ID);

        verify(rbacService).canAccessCompany(any(LoginUser.class), eq(COMPANY_ID));
    }

    @Test
    @DisplayName("项目经 ofProject 取公司并返回（顺带断言公司确实流回调用方）")
    void projectResolvesCompanyThroughOfProject() {
        when(projectMapper.selectOne(any())).thenReturn(new Project());
        when(ownershipResolver.ofProject(PROJECT_ID)).thenReturn(COMPANY_ID);
        when(rbacService.canAccessCompany(any(LoginUser.class), eq(COMPANY_ID))).thenReturn(true);
        login();

        assertThat(resolver.assertAccessible(RecordOwnerType.PROJECT, PROJECT_ID)).isEqualTo(COMPANY_ID);

        verify(rbacService).canAccessCompany(any(LoginUser.class), eq(COMPANY_ID));
    }

    @Test
    @DisplayName("资产经 ofAsset 取公司并返回")
    void assetResolvesCompanyThroughOfAsset() {
        when(assetMapper.selectOne(any())).thenReturn(new Asset());
        when(ownershipResolver.ofAsset(ASSET_ID)).thenReturn(COMPANY_ID);
        when(rbacService.canAccessCompany(any(LoginUser.class), eq(COMPANY_ID))).thenReturn(true);
        login();

        assertThat(resolver.assertAccessible(RecordOwnerType.ASSET, ASSET_ID)).isEqualTo(COMPANY_ID);

        verify(rbacService).canAccessCompany(any(LoginUser.class), eq(COMPANY_ID));
    }

    @Test
    @DisplayName("分区不存在（含已软删）→ 404，且不做数据范围断言")
    void missingZoneIsNotFound() {
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.ZONE, ZONE_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND)
                .hasMessageContaining("不存在");
        verifyNoInteractions(rbacService);
    }

    @Test
    @DisplayName("项目不存在 → 404，且不做数据范围断言")
    void missingProjectIsNotFound() {
        when(projectMapper.selectOne(any())).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.PROJECT, PROJECT_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND)
                .hasMessageContaining("不存在");
        verifyNoInteractions(rbacService);
    }

    @Test
    @DisplayName("资产不存在 → 404，且不做数据范围断言")
    void missingAssetIsNotFound() {
        when(assetMapper.selectOne(any())).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.ASSET, ASSET_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
        verifyNoInteractions(rbacService);
    }

    @Test
    @DisplayName("已软删的资产按不存在处理 → 404（不落成可挂记录的宿主）")
    void softDeletedAssetIsNotFound() {
        // 真实查询带 deleted_at IS NULL，软删的行取不出来 → 等价于「不存在」
        when(assetMapper.selectOne(any())).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.ASSET, ASSET_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
        verifyNoInteractions(rbacService, ownershipResolver);
    }

    @Test
    @DisplayName("已软删的项目按不存在处理 → 404（不落成可挂记录的宿主）")
    void softDeletedProjectIsNotFound() {
        when(projectMapper.selectOne(any())).thenReturn(null);
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.PROJECT, PROJECT_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
        verifyNoInteractions(rbacService, ownershipResolver);
    }

    @Test
    @DisplayName("ownerId 缺失 → 400（断言错误码，文案区分不出 400 与 404）")
    void nullOwnerIdIsBadRequest() {
        login();

        assertThatThrownBy(() -> resolver.assertAccessible(RecordOwnerType.ASSET, null))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST)
                .hasMessageContaining("归属对象");
        verifyNoInteractions(assetMapper, ownershipResolver, rbacService);
    }

    @Test
    @DisplayName("资产存在但归属推导不出公司 → 返回 null，仍交给 canAccessCompany 判定")
    void assetWithoutCompanyStillGoesThroughScopeCheck() {
        when(assetMapper.selectOne(any())).thenReturn(new Asset());
        when(ownershipResolver.ofAsset(ASSET_ID)).thenReturn(null);
        when(rbacService.canAccessCompany(any(LoginUser.class), isNull())).thenReturn(true);
        login();

        assertThat(resolver.assertAccessible(RecordOwnerType.ASSET, ASSET_ID)).isNull();

        verify(rbacService).canAccessCompany(any(LoginUser.class), isNull());
    }

    @Test
    @DisplayName("对象级越权与宿主不存在不可区分：同 code 同文案（设计 §5.3 第 5 条 / 验收 8）")
    void outOfScopeIsIndistinguishableFromMissing() {
        login();

        // 1) 宿主不存在
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(null);
        AppException missing = catchThrowableOfType(
                () -> resolver.assertAccessible(RecordOwnerType.ZONE, ZONE_ID), AppException.class);

        // 2) 宿主存在，但归属公司不在当前账号的数据范围内
        ProjectZone zone = new ProjectZone();
        zone.setId(ZONE_ID);
        zone.setProjectId(PROJECT_ID);
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(zone);
        when(ownershipResolver.ofProject(PROJECT_ID)).thenReturn(COMPANY_ID);
        when(rbacService.canAccessCompany(any(LoginUser.class), eq(COMPANY_ID))).thenReturn(false);
        AppException outOfScope = catchThrowableOfType(
                () -> resolver.assertAccessible(RecordOwnerType.ZONE, ZONE_ID), AppException.class);

        // 只断「抛了 404」还不够：文案只要不同，越权仍可被当成存在性探针
        assertThat(missing).isNotNull();
        assertThat(outOfScope).isNotNull();
        assertThat(missing.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(outOfScope.getErrorCode()).isEqualTo(missing.getErrorCode());
        assertThat(outOfScope.getMessage()).isEqualTo(missing.getMessage());
    }

    @Test
    @DisplayName("带项目期望：分区确实属于该项目 → 正常返回归属公司")
    void zoneInProjectResolvesCompanyThroughProject() {
        ProjectZone zone = new ProjectZone();
        zone.setId(ZONE_ID);
        zone.setProjectId(PROJECT_ID);
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(zone);
        when(ownershipResolver.ofProject(PROJECT_ID)).thenReturn(COMPANY_ID);
        when(rbacService.canAccessCompany(any(LoginUser.class), eq(COMPANY_ID))).thenReturn(true);
        login();

        assertThat(resolver.assertAccessibleInProject(PROJECT_ID, ZONE_ID)).isEqualTo(COMPANY_ID);

        verify(rbacService).canAccessCompany(any(LoginUser.class), eq(COMPANY_ID));
    }

    @Test
    @DisplayName("带项目期望：分区不属于路径里的项目 → 404，文案与「分区不存在」逐字相同")
    void zoneOutsideProjectIsNotFoundWithSameMessageAsMissing() {
        login();

        // 1) 分区不存在
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(null);
        AppException missing = catchThrowableOfType(
                () -> resolver.assertAccessibleInProject(PROJECT_ID, ZONE_ID), AppException.class);

        // 2) 分区存在，但属于另一个项目：projectId 配不配同样是「不存在」，不做存在性探针
        ProjectZone otherProjectZone = new ProjectZone();
        otherProjectZone.setId(ZONE_ID);
        otherProjectZone.setProjectId(PROJECT_ID + 1);
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(otherProjectZone);
        AppException wrongProject = catchThrowableOfType(
                () -> resolver.assertAccessibleInProject(PROJECT_ID, ZONE_ID), AppException.class);

        assertThat(missing).isNotNull();
        assertThat(wrongProject).isNotNull();
        assertThat(missing.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(wrongProject.getErrorCode()).isEqualTo(missing.getErrorCode());
        assertThat(wrongProject.getMessage()).isEqualTo(missing.getMessage());
        // 项目不匹配在解析归属之前就拒绝：不能顺手多查一次公司，否则又成了探针
        verifyNoInteractions(ownershipResolver, rbacService);
    }

    @Test
    @DisplayName("带项目期望：分区属于该项目但归属公司越权 → 仍然 404，文案与不存在相同")
    void zoneInProjectOutOfScopeIsNotFoundWithSameMessage() {
        login();

        ProjectZone zone = new ProjectZone();
        zone.setId(ZONE_ID);
        zone.setProjectId(PROJECT_ID);
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(zone);
        when(ownershipResolver.ofProject(PROJECT_ID)).thenReturn(COMPANY_ID);
        when(rbacService.canAccessCompany(any(LoginUser.class), eq(COMPANY_ID))).thenReturn(false);
        AppException outOfScope = catchThrowableOfType(
                () -> resolver.assertAccessibleInProject(PROJECT_ID, ZONE_ID), AppException.class);

        // 与「分区不存在」同一文案：证明新增的项目校验分支没有把 403 引回来
        when(projectZoneMapper.selectActiveById(ZONE_ID)).thenReturn(null);
        AppException missing = catchThrowableOfType(
                () -> resolver.assertAccessibleInProject(PROJECT_ID, ZONE_ID), AppException.class);

        assertThat(outOfScope).isNotNull();
        assertThat(missing).isNotNull();
        assertThat(outOfScope.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(missing.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(outOfScope.getMessage()).isEqualTo(missing.getMessage());
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
