package com.ams.modules.asset.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.service.AssetService;
import com.ams.platform.security.Audited;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目与资产台账接口（FR-AST-001/002/003）。
 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    // ---- 项目 ----
    @GetMapping("/projects")
    public ApiResponse<PageResult<Project>> projects(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(assetService.pageProjects(page, pageSize, keyword, companyId), TraceIdUtil.get());
    }

    @GetMapping("/projects/{id}")
    public ApiResponse<Project> project(@PathVariable Long id) {
        return ApiResponse.ok(assetService.getProject(id), TraceIdUtil.get());
    }

    @PostMapping("/projects")
    @Audited(module = "asset", action = "create_project")
    public ApiResponse<Project> createProject(@RequestBody Project project) {
        return ApiResponse.ok(assetService.createProject(project), TraceIdUtil.get());
    }

    @PutMapping("/projects/{id}")
    @Audited(module = "asset", action = "update_project")
    public ApiResponse<Project> updateProject(@PathVariable Long id, @RequestBody Project project) {
        return ApiResponse.ok(assetService.updateProject(id, project), TraceIdUtil.get());
    }

    // ---- 资产 ----
    @GetMapping("/assets")
    public ApiResponse<PageResult<Asset>> assets(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String ownershipType,
            @RequestParam(required = false) String leaseControlStatus,
            @RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(assetService.pageAssets(page, pageSize, assetType, keyword,
                sourceType, ownershipType, leaseControlStatus, companyId), TraceIdUtil.get());
    }

    @GetMapping("/assets/{assetId}")
    public ApiResponse<Asset> asset(@PathVariable Long assetId) {
        return ApiResponse.ok(assetService.getAsset(assetId), TraceIdUtil.get());
    }

    @PostMapping("/assets")
    @Audited(module = "asset", action = "create_asset")
    public ApiResponse<Asset> createAsset(@RequestBody Asset asset) {
        return ApiResponse.ok(assetService.createAsset(asset), TraceIdUtil.get());
    }

    @PutMapping("/assets/{assetId}")
    @Audited(module = "asset", action = "update_asset")
    public ApiResponse<Asset> updateAsset(@PathVariable Long assetId, @RequestBody Asset asset) {
        return ApiResponse.ok(assetService.updateAsset(assetId, asset), TraceIdUtil.get());
    }

    @DeleteMapping("/assets/{assetId}")
    @Audited(module = "asset", action = "delete_asset")
    public ApiResponse<Void> deleteAsset(@PathVariable Long assetId) {
        assetService.deleteAsset(assetId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
}
