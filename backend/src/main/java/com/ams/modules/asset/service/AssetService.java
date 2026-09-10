package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.ProjectMapper;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 项目与资产台账（FR-AST-001/002/003）。
 */
@Service
public class AssetService {

    private final ProjectMapper projectMapper;
    private final AssetMapper assetMapper;
    private final AssetQrService assetQrService;
    private final CompanyTreeService companyTreeService;

    public AssetService(
            ProjectMapper projectMapper,
            AssetMapper assetMapper,
            AssetQrService assetQrService,
            CompanyTreeService companyTreeService) {
        this.projectMapper = projectMapper;
        this.assetMapper = assetMapper;
        this.assetQrService = assetQrService;
        this.companyTreeService = companyTreeService;
    }

    // ---- 项目 ----
    public PageResult<Project> pageProjects(long page, long pageSize, String keyword, Long companyId) {
        Page<Project> result = projectMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<Project>()
                        .like(keyword != null && !keyword.isBlank(), Project::getName, keyword)
                        .eq(companyId != null, Project::getCompanyId, companyId)
                        .orderByDesc(Project::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public Project getProject(Long id) {
        Project p = projectMapper.selectById(id);
        if (p == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return p;
    }

    public Project createProject(Project project) {
        projectMapper.insert(project);
        return project;
    }

    public Project updateProject(Long id, Project project) {
        project.setId(id);
        projectMapper.updateById(project);
        return getProject(id);
    }

    // ---- 资产 ----
    public PageResult<Asset> pageAssets(long page, long pageSize, String assetType, String keyword,
            String sourceType, String ownershipType, String leaseControlStatus, Long companyId) {
        LoginUser user = SecurityUtils.current();
        Set<Long> scope = companyScope(user);
        Page<Asset> result = assetMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<Asset>()
                        .eq(assetType != null, Asset::getAssetType, assetType)
                        .eq(sourceType != null, Asset::getSourceType, sourceType)
                        .eq(ownershipType != null, Asset::getOwnershipType, ownershipType)
                        .eq(leaseControlStatus != null, Asset::getLeaseControlStatus, leaseControlStatus)
                        .eq(companyId != null, Asset::getOperatingCompanyId, companyId)
                        .and(scope != null && !scope.isEmpty(),
                                w -> w.in(Asset::getOperatingCompanyId, scope))
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(Asset::getName, keyword).or().like(Asset::getAssetNo, keyword))
                        .orderByDesc(Asset::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public Asset getAsset(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return asset;
    }

    public Asset createAsset(Asset asset) {
        if (asset.getLeaseControlStatus() == null) {
            asset.setLeaseControlStatus("vacant");
        }
        if (asset.getStructureStatus() == null) {
            asset.setStructureStatus("active");
        }
        asset.setVersion(0);
        assetMapper.insert(asset);
        return assetQrService.ensureQrCode(asset);
    }

    public Asset updateAsset(Long id, Asset asset) {
        Asset existing = getAsset(id);
        asset.setId(id);
        // 基础信息可改，租控状态不可通过此接口直改（须走业务单据）
        asset.setLeaseControlStatus(existing.getLeaseControlStatus());
        assetMapper.updateById(asset);
        return getAsset(id);
    }

    public void deleteAsset(Long id) {
        Asset asset = getAsset(id);
        if (!"vacant".equals(asset.getLeaseControlStatus())) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "非空置资产不可删除");
        }
        assetMapper.deleteById(id);
    }

    /**
     * 数据范围：可访问公司集合（本公司 + 全部下级公司子树）。
     *
     * @return {@code null} 表示全量（super_admin / all）；否则为限定集合。
     *         非全量用户若未归属公司，返回哨兵集合而非空集合 —— 空集合会被
     *         调用处的 {@code !scope.isEmpty()} 判定为「不加条件」，导致越权看到全量数据。
     */
    private Set<Long> companyScope(LoginUser user) {
        if (user == null || user.isSuperAdmin() || "all".equals(user.getDataScope())) {
            return null; // null 表示全量
        }
        if (user.getCompanyId() == null) {
            return Set.of(-1L); // 哨兵：不匹配任何公司，避免退化为全量
        }
        Set<Long> scope = new LinkedHashSet<>();
        scope.add(user.getCompanyId());
        scope.addAll(companyTreeService.descendantIds(user.getCompanyId()));
        return scope;
    }
}
