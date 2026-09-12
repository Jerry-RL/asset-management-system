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
import com.ams.platform.security.RequiresPerm;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
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
    private final OwnershipResolver ownershipResolver;
    private final RbacService rbacService;

    public AssetController(
            AssetService assetService,
            AssetStructureService structureService,
            AssetDossierService dossierService,
            AssetQrService assetQrService,
            OwnershipResolver ownershipResolver,
            RbacService rbacService) {
        this.assetService = assetService;
        this.structureService = structureService;
        this.dossierService = dossierService;
        this.assetQrService = assetQrService;
        this.ownershipResolver = ownershipResolver;
        this.rbacService = rbacService;
    }

    // ---- 项目 ----
    @GetMapping("/projects")
    @RequiresPerm("asset.project:view")
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
    @RequiresPerm("asset.project:view")
    public ApiResponse<ProjectStats> projectSummary(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(assetService.projectStats(keyword, companyId), TraceIdUtil.get());
    }

    @GetMapping("/projects/{id}")
    @RequiresPerm("asset.project:view")
    public ApiResponse<Project> project(@PathVariable Long id) {
        assertProject(id);
        return ApiResponse.ok(assetService.getProject(id), TraceIdUtil.get());
    }

    /** 项目详情页聚合视图：主体 + 资产基本信息 / 资产创收 / 租赁概况 + 分区汇总 */
    @GetMapping("/projects/{id}/overview")
    @RequiresPerm("asset.project:view")
    public ApiResponse<ProjectOverview> projectOverview(@PathVariable Long id) {
        assertProject(id);
        return ApiResponse.ok(assetService.projectOverview(id), TraceIdUtil.get());
    }

    /** 项目分区列表（第二步配置内容） */
    @GetMapping("/projects/{id}/zones")
    @RequiresPerm("asset.project:view")
    public ApiResponse<List<ProjectZone>> projectZones(@PathVariable Long id) {
        assertProject(id);
        return ApiResponse.ok(assetService.listProjectZones(id), TraceIdUtil.get());
    }

    /**
     * 新增分区（项目列表展开行内维护）。
     *
     * <p>归属由路径参数决定：请求体里的 {@code id} / {@code projectId} 在服务层被忽略。
     */
    @PostMapping("/projects/{id}/zones")
    @RequiresPerm("asset.project:update")
    @Audited(module = "asset", action = "create_project_zone")
    public ApiResponse<ProjectZone> createProjectZone(@PathVariable Long id,
            @RequestBody ProjectZone zone) {
        assertProject(id);
        return ApiResponse.ok(assetService.createProjectZone(id, zone), TraceIdUtil.get());
    }

    /** 编辑分区（项目列表展开行内维护）。 */
    @PutMapping("/projects/{id}/zones/{zoneId}")
    @RequiresPerm("asset.project:update")
    @Audited(module = "asset", action = "update_project_zone")
    public ApiResponse<ProjectZone> updateProjectZone(@PathVariable Long id,
            @PathVariable Long zoneId, @RequestBody ProjectZone zone) {
        assertProject(id);
        return ApiResponse.ok(assetService.updateProjectZone(id, zoneId, zone), TraceIdUtil.get());
    }

    /** 删除分区（项目列表展开行内维护；分区下有资产时返回 400 并提示数量）。 */
    @DeleteMapping("/projects/{id}/zones/{zoneId}")
    @RequiresPerm("asset.project:update")
    @Audited(module = "asset", action = "delete_project_zone")
    public ApiResponse<Void> deleteProjectZone(@PathVariable Long id, @PathVariable Long zoneId) {
        assertProject(id);
        assetService.deleteProjectZone(id, zoneId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/projects")
    @RequiresPerm("asset.project:create")
    @Audited(module = "asset", action = "create_project")
    public ApiResponse<Project> createProject(@RequestBody ProjectSaveRequest request) {
        // 新建：请求体给出的公司是唯一归属来源
        rbacService.assertCompanyAccess(SecurityUtils.current(), request.getCompanyId());
        return ApiResponse.ok(assetService.createProject(request), TraceIdUtil.get());
    }

    @PutMapping("/projects/{id}")
    @RequiresPerm("asset.project:update")
    @Audited(module = "asset", action = "update_project")
    public ApiResponse<Project> updateProject(@PathVariable Long id,
            @RequestBody ProjectSaveRequest request) {
        assertProject(id);
        return ApiResponse.ok(assetService.updateProject(id, request), TraceIdUtil.get());
    }

    @DeleteMapping("/projects/{id}")
    @RequiresPerm("asset.project:delete")
    @Audited(module = "asset", action = "delete_project")
    public ApiResponse<Void> deleteProject(@PathVariable Long id) {
        assertProject(id);
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
    @RequiresPerm("asset.ledger:view")
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
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<Asset> asset(@PathVariable Long assetId) {
        assertAsset(assetId);
        return ApiResponse.ok(assetService.getAsset(assetId), TraceIdUtil.get());
    }

    /** 一物一档：某一资产当前状态与全链路历史（FR-AST-003）。 */
    @GetMapping("/assets/{assetId}/dossier")
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<AssetDossier> dossier(@PathVariable Long assetId) {
        assertAsset(assetId);
        return ApiResponse.ok(dossierService.getDossier(assetId), TraceIdUtil.get());
    }

    /** 一产一码：下载资产二维码 PNG（扫码打开用户端招租/资产页）。 */
    @GetMapping("/assets/{assetId}/qrcode")
    @RequiresPerm("asset.ledger:view")
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
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<Map<String, String>> qrcodeUrl(@PathVariable Long assetId) {
        Asset asset = assetQrService.ensureQrCode(assetId);
        return ApiResponse.ok(Map.of(
                "qrCodeUrl", asset.getQrCodeUrl(),
                "assetNo", asset.getAssetNo() == null ? "" : asset.getAssetNo()), TraceIdUtil.get());
    }

    @PostMapping("/assets")
    @RequiresPerm("asset.ledger:create")
    @Audited(module = "asset", action = "create_asset")
    public ApiResponse<Asset> createAsset(@RequestBody Asset asset) {
        // 新建：经营公司优先，缺失时回落到项目所属公司；两者都没有则受限账号被拒
        rbacService.assertCompanyAccess(SecurityUtils.current(),
                asset.getOperatingCompanyId() != null
                        ? asset.getOperatingCompanyId()
                        : ownershipResolver.ofProject(asset.getProjectId()));
        return ApiResponse.ok(assetService.createAsset(asset), TraceIdUtil.get());
    }

    @PutMapping("/assets/{assetId}")
    @RequiresPerm("asset.ledger:update")
    @Audited(module = "asset", action = "update_asset")
    public ApiResponse<Asset> updateAsset(@PathVariable Long assetId, @RequestBody Asset asset) {
        assertAsset(assetId);
        return ApiResponse.ok(assetService.updateAsset(assetId, asset), TraceIdUtil.get());
    }

    @DeleteMapping("/assets/{assetId}")
    @RequiresPerm("asset.ledger:delete")
    @Audited(module = "asset", action = "delete_asset")
    public ApiResponse<Void> deleteAsset(@PathVariable Long assetId) {
        assertAsset(assetId);
        assetService.deleteAsset(assetId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    // ---- 主数据拆分合并 ----
    @PostMapping("/assets/{assetId}/split")
    @RequiresPerm("asset.ledger:update")
    @Audited(module = "asset", action = "split")
    public ApiResponse<Map<String, Object>> split(
            @PathVariable Long assetId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawAreas = (List<Object>) body.get("childAreas");
        assertAsset(assetId);
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
    @RequiresPerm("asset.ledger:update")
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
        // 合并会改写每一条源资产，必须逐条确认都在范围内
        ids.forEach(this::assertAsset);
        return ApiResponse.ok(structureService.merge(ids, (String) body.get("name"), (String) body.get("remark")),
                TraceIdUtil.get());
    }

    @GetMapping("/assets/{assetId}/structure-tree")
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<Map<String, Object>> structureTree(@PathVariable Long assetId) {
        assertAsset(assetId);
        return ApiResponse.ok(structureService.structureTree(assetId), TraceIdUtil.get());
    }

    @GetMapping("/assets/code-mappings")
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<List<AssetCodeMapping>> codeMappings(@RequestParam(required = false) String oldAssetNo) {
        return ApiResponse.ok(structureService.codeMappings(oldAssetNo), TraceIdUtil.get());
    }

    @GetMapping("/assets/structure-logs")
    @RequiresPerm("asset.structureLog:view")
    public ApiResponse<List<AssetStructureLog>> structureLogs(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(structureService.listLogs(limit), TraceIdUtil.get());
    }

    private void assertProject(Long id) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofProject(id));
    }

    private void assertAsset(Long assetId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofAsset(assetId));
    }
}
