package com.ams.modules.audit.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.audit.entity.AssetAuditItem;
import com.ams.modules.audit.entity.AssetAuditPlan;
import com.ams.modules.audit.service.AssetAuditService;
import com.ams.platform.security.Audited;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 经营性盘点接口（FR-AST-AUDIT-*）。
 */
@RestController
@RequestMapping("/api/v1/asset-audits")
public class AssetAuditController {

    private final AssetAuditService assetAuditService;

    public AssetAuditController(AssetAuditService assetAuditService) {
        this.assetAuditService = assetAuditService;
    }

    @GetMapping
    public ApiResponse<PageResult<AssetAuditPlan>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(assetAuditService.pagePlans(page, pageSize, status), TraceIdUtil.get());
    }

    @GetMapping("/{planId}")
    public ApiResponse<AssetAuditPlan> get(@PathVariable Long planId) {
        return ApiResponse.ok(assetAuditService.getPlan(planId), TraceIdUtil.get());
    }

    @PostMapping
    @Audited(module = "asset_audit", action = "create")
    public ApiResponse<AssetAuditPlan> create(@RequestBody Map<String, Object> body) {
        AssetAuditPlan plan = new AssetAuditPlan();
        plan.setTitle(body.get("title") == null ? "经营性盘点" : body.get("title").toString());
        if (body.get("companyId") != null) {
            plan.setCompanyId(Long.valueOf(body.get("companyId").toString()));
        }
        if (body.get("projectId") != null) {
            plan.setProjectId(Long.valueOf(body.get("projectId").toString()));
        }
        if (body.get("scopeType") != null) {
            plan.setScopeType(body.get("scopeType").toString());
        }
        if (body.get("plannedStart") != null) {
            plan.setPlannedStart(LocalDate.parse(body.get("plannedStart").toString()));
        }
        if (body.get("plannedEnd") != null) {
            plan.setPlannedEnd(LocalDate.parse(body.get("plannedEnd").toString()));
        }
        plan.setRemark(body.get("remark") == null ? null : body.get("remark").toString());
        return ApiResponse.ok(assetAuditService.createPlan(plan), TraceIdUtil.get());
    }

    @PostMapping("/{planId}/start")
    @Audited(module = "asset_audit", action = "start")
    public ApiResponse<AssetAuditPlan> start(@PathVariable Long planId) {
        return ApiResponse.ok(assetAuditService.startPlan(planId), TraceIdUtil.get());
    }

    @GetMapping("/{planId}/items")
    public ApiResponse<List<AssetAuditItem>> items(
            @PathVariable Long planId, @RequestParam(required = false) String status) {
        return ApiResponse.ok(assetAuditService.listItems(planId, status), TraceIdUtil.get());
    }

    @PostMapping("/{planId}/scan")
    @Audited(module = "asset_audit", action = "scan")
    public ApiResponse<AssetAuditItem> scan(@PathVariable Long planId, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(assetAuditService.scan(planId, body.get("scanCode"),
                body.get("actualStatus"), body.get("remark")), TraceIdUtil.get());
    }

    @PostMapping("/{planId}/complete")
    @Audited(module = "asset_audit", action = "complete")
    public ApiResponse<AssetAuditPlan> complete(@PathVariable Long planId) {
        return ApiResponse.ok(assetAuditService.completePlan(planId), TraceIdUtil.get());
    }

    @GetMapping("/{planId}/report")
    public ApiResponse<Map<String, Object>> report(@PathVariable Long planId) {
        return ApiResponse.ok(assetAuditService.report(planId), TraceIdUtil.get());
    }
}
