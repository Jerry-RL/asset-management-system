package com.ams.modules.asset.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.asset.dto.AssetDossier;
import com.ams.modules.asset.dto.ProjectOverview;
import com.ams.modules.asset.dto.ProjectSaveRequest;
import com.ams.modules.asset.dto.ProjectStats;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetCodeMapping;
import com.ams.modules.asset.entity.AssetStructureLog;
import com.ams.modules.asset.entity.Project;
import com.ams.modules.asset.entity.ProjectZone;
import com.ams.modules.asset.service.AssetDossierService;
import com.ams.modules.asset.service.AssetQrService;
import com.ams.modules.asset.service.AssetService;
import com.ams.modules.asset.service.AssetStructureService;
import com.ams.platform.security.Audited;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * 项目与资产台账接口（FR-AST-001/002/003、FR-MDM-*）。
 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {

    private final AssetService assetService;
    private final AssetStructureService structureService;
    private final AssetDossierService dossierService;
    private final AssetQrService assetQrService;

    public AssetController(
            AssetService assetService,
            AssetStructureService structureService,
            AssetDossierService dossierService,
            AssetQrService assetQrService) {
        this.assetService = assetService;
        this.structureService = structureService;
        this.dossierService = dossierService;
        this.assetQrService = assetQrService;
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

    /**
     * 项目管理顶部统计条：项目数 / 资产宗数 / 资产利用率 / 闲置宗数 / 盘活宗数。
     *
     * <p>与 {@code /projects} 使用同一套筛选条件与数据范围，保证统计与列表对账一致。
     * 路径为字面量，Spring 会优先于 {@code /projects/{id}} 匹配，不会误入详情。
     */
    @GetMapping("/projects/summary")
    public ApiResponse<ProjectStats> projectSummary(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(assetService.projectStats(keyword, companyId), TraceIdUtil.get());
    }

    @GetMapping("/projects/{id}")
    public ApiResponse<Project> project(@PathVariable Long id) {
        return ApiResponse.ok(assetService.getProject(id), TraceIdUtil.get());
    }

    /** 项目详情页聚合视图：主体 + 资产基本信息 / 资产创收 / 租赁概况 + 分区汇总 */
    @GetMapping("/projects/{id}/overview")
    public ApiResponse<ProjectOverview> projectOverview(@PathVariable Long id) {
        return ApiResponse.ok(assetService.projectOverview(id), TraceIdUtil.get());
    }

    /** 项目分区列表（第二步配置内容） */
    @GetMapping("/projects/{id}/zones")
    public ApiResponse<List<ProjectZone>> projectZones(@PathVariable Long id) {
        return ApiResponse.ok(assetService.listProjectZones(id), TraceIdUtil.get());
    }

    @PostMapping("/projects")
    @Audited(module = "asset", action = "create_project")
    public ApiResponse<Project> createProject(@RequestBody ProjectSaveRequest request) {
        return ApiResponse.ok(assetService.createProject(request), TraceIdUtil.get());
    }

    @PutMapping("/projects/{id}")
    @Audited(module = "asset", action = "update_project")
    public ApiResponse<Project> updateProject(@PathVariable Long id,
            @RequestBody ProjectSaveRequest request) {
        return ApiResponse.ok(assetService.updateProject(id, request), TraceIdUtil.get());
    }

    @DeleteMapping("/projects/{id}")
    @Audited(module = "asset", action = "delete_project")
    public ApiResponse<Void> deleteProject(@PathVariable Long id) {
        assetService.deleteProject(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    // ---- 资产 ----

    /**
     * 资产分页查询。
     *
     * @param projectType 「项目属性」筛选（所属项目的 type，取值见
     *                    sys_dict_type.code = project_property），与「资产来源」级联筛选配合
     */
    @GetMapping("/assets")
    public ApiResponse<PageResult<Asset>> assets(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String ownershipType,
            @RequestParam(required = false) String leaseControlStatus,
            @RequestParam(required = false) String projectType,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long zoneId) {
        return ApiResponse.ok(assetService.pageAssets(page, pageSize, assetType, keyword,
                sourceType, ownershipType, leaseControlStatus, companyId, projectType, projectId,
                zoneId), TraceIdUtil.get());
    }

    @GetMapping("/assets/{assetId}")
    public ApiResponse<Asset> asset(@PathVariable Long assetId) {
        return ApiResponse.ok(assetService.getAsset(assetId), TraceIdUtil.get());
    }

    /** 一物一档：某一资产当前状态与全链路历史（FR-AST-003）。 */
    @GetMapping("/assets/{assetId}/dossier")
    public ApiResponse<AssetDossier> dossier(@PathVariable Long assetId) {
        return ApiResponse.ok(dossierService.getDossier(assetId), TraceIdUtil.get());
    }

    /** 一产一码：下载资产二维码 PNG（扫码打开用户端招租/资产页）。 */
    @GetMapping("/assets/{assetId}/qrcode")
    public ResponseEntity<byte[]> qrcode(
            @PathVariable Long assetId,
            @RequestParam(defaultValue = "512") int size) {
        Asset asset = assetQrService.ensureQrCode(assetId);
        byte[] png = assetQrService.generatePng(assetId, size);
        String fileName = "asset-" + (asset.getAssetNo() != null ? asset.getAssetNo() : assetId) + "-qrcode.png";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    /** 返回扫码链接（便于前端预览二维码内容）。 */
    @GetMapping("/assets/{assetId}/qrcode-url")
    public ApiResponse<Map<String, String>> qrcodeUrl(@PathVariable Long assetId) {
        Asset asset = assetQrService.ensureQrCode(assetId);
        return ApiResponse.ok(Map.of(
                "qrCodeUrl", asset.getQrCodeUrl(),
                "assetNo", asset.getAssetNo() == null ? "" : asset.getAssetNo()), TraceIdUtil.get());
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

    // ---- 主数据拆分合并 ----
    @PostMapping("/assets/{assetId}/split")
    @Audited(module = "asset", action = "split")
    public ApiResponse<Map<String, Object>> split(
            @PathVariable Long assetId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawAreas = (List<Object>) body.get("childAreas");
        List<BigDecimal> areas = new ArrayList<>();
        if (rawAreas != null) {
            for (Object o : rawAreas) {
                areas.add(new BigDecimal(o.toString()));
            }
        }
        return ApiResponse.ok(structureService.split(
                assetId, areas, (String) body.get("contractStrategy"), (String) body.get("remark")),
                TraceIdUtil.get());
    }

    @PostMapping("/assets/merge")
    @Audited(module = "asset", action = "merge")
    public ApiResponse<Map<String, Object>> merge(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("sourceAssetIds");
        List<Long> ids = new ArrayList<>();
        if (rawIds != null) {
            for (Object o : rawIds) {
                ids.add(Long.valueOf(o.toString()));
            }
        }
        return ApiResponse.ok(structureService.merge(ids, (String) body.get("name"), (String) body.get("remark")),
                TraceIdUtil.get());
    }

    @GetMapping("/assets/{assetId}/structure-tree")
    public ApiResponse<Map<String, Object>> structureTree(@PathVariable Long assetId) {
        return ApiResponse.ok(structureService.structureTree(assetId), TraceIdUtil.get());
    }

    @GetMapping("/assets/code-mappings")
    public ApiResponse<List<AssetCodeMapping>> codeMappings(@RequestParam(required = false) String oldAssetNo) {
        return ApiResponse.ok(structureService.codeMappings(oldAssetNo), TraceIdUtil.get());
    }

    @GetMapping("/assets/structure-logs")
    public ApiResponse<List<AssetStructureLog>> structureLogs(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(structureService.listLogs(limit), TraceIdUtil.get());
    }
}
