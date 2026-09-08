package com.ams.modules.misc.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.dashboard.service.DashboardService;
import com.ams.modules.evaluation.entity.EvaluationRequest;
import com.ams.modules.evaluation.service.EvaluationService;
import com.ams.modules.migration.entity.MigrationBatch;
import com.ams.modules.migration.entity.MigrationImportLog;
import com.ams.modules.migration.service.MigrationService;
import com.ams.modules.plan.dto.PlanDeviationResult;
import com.ams.modules.plan.entity.BusinessPlan;
import com.ams.modules.plan.service.BusinessPlanService;
import com.ams.modules.revitalization.entity.RevitalizationTask;
import com.ams.modules.revitalization.service.RevitalizationService;
import com.ams.platform.security.Audited;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 看板 / 评估 / 盘活 / 经营计划 / 期初迁移 接口。
 */
@RestController
@RequestMapping("/api/v1")
public class MiscController {

    private final DashboardService dashboardService;
    private final EvaluationService evaluationService;
    private final RevitalizationService revitalizationService;
    private final BusinessPlanService businessPlanService;
    private final MigrationService migrationService;

    public MiscController(
            DashboardService dashboardService,
            EvaluationService evaluationService,
            RevitalizationService revitalizationService,
            BusinessPlanService businessPlanService,
            MigrationService migrationService) {
        this.dashboardService = dashboardService;
        this.evaluationService = evaluationService;
        this.revitalizationService = revitalizationService;
        this.businessPlanService = businessPlanService;
        this.migrationService = migrationService;
    }

    // ---- 看板 ----
    @GetMapping("/dashboard/operations")
    public ApiResponse<Map<String, Object>> operations(@RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(dashboardService.operations(companyId), TraceIdUtil.get());
    }

    @GetMapping("/dashboard/consolidate")
    public ApiResponse<Map<String, Object>> consolidate() {
        return ApiResponse.ok(dashboardService.consolidate(), TraceIdUtil.get());
    }

    @GetMapping("/dashboard/consolidate/drill")
    public ApiResponse<Map<String, Object>> consolidateDrill(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(dashboardService.drill(companyId, projectId), TraceIdUtil.get());
    }

    // ---- 评估 ----
    @GetMapping("/evaluations")
    public ApiResponse<List<EvaluationRequest>> evaluations(@RequestParam(required = false) Long assetId) {
        return ApiResponse.ok(evaluationService.list(assetId), TraceIdUtil.get());
    }

    @PostMapping("/evaluations")
    @Audited(module = "evaluation", action = "apply")
    public ApiResponse<EvaluationRequest> applyEvaluation(@RequestBody EvaluationRequest request) {
        return ApiResponse.ok(evaluationService.apply(request), TraceIdUtil.get());
    }

    @PostMapping("/evaluations/{id}/accept")
    @Audited(module = "evaluation", action = "accept")
    public ApiResponse<EvaluationRequest> accept(@PathVariable Long id) {
        return ApiResponse.ok(evaluationService.accept(id), TraceIdUtil.get());
    }

    @PostMapping("/evaluations/{id}/evaluating")
    @Audited(module = "evaluation", action = "evaluating")
    public ApiResponse<EvaluationRequest> evaluating(@PathVariable Long id) {
        return ApiResponse.ok(evaluationService.startEvaluating(id), TraceIdUtil.get());
    }

    @PostMapping("/evaluations/{id}/result")
    @Audited(module = "evaluation", action = "record_result")
    public ApiResponse<EvaluationRequest> recordResult(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BigDecimal value = new BigDecimal(body.get("resultValue").toString());
        Long reportFileId = body.get("reportFileId") == null ? null
                : Long.valueOf(body.get("reportFileId").toString());
        Boolean syncFloor = body.get("syncFloor") == null ? null
                : Boolean.parseBoolean(body.get("syncFloor").toString());
        return ApiResponse.ok(evaluationService.recordResult(id, value, reportFileId, syncFloor), TraceIdUtil.get());
    }

    // ---- 盘活 ----
    @GetMapping("/revitalization")
    public ApiResponse<List<RevitalizationTask>> revitalization(@RequestParam(required = false) String status) {
        return ApiResponse.ok(revitalizationService.list(status), TraceIdUtil.get());
    }

    @PostMapping("/revitalization")
    @Audited(module = "revitalization", action = "create")
    public ApiResponse<RevitalizationTask> createRevitalization(@RequestBody RevitalizationTask task) {
        return ApiResponse.ok(revitalizationService.create(task), TraceIdUtil.get());
    }

    @PostMapping("/revitalization/{id}/status")
    @Audited(module = "revitalization", action = "update_status")
    public ApiResponse<RevitalizationTask> updateRevitalization(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(revitalizationService.updateStatus(id, body.get("status")), TraceIdUtil.get());
    }

    @GetMapping("/revitalization/dashboard")
    public ApiResponse<Map<String, Object>> revitalizationDashboard() {
        return ApiResponse.ok(revitalizationService.dashboard(), TraceIdUtil.get());
    }

    // ---- 经营计划 ----
    @GetMapping("/business-plans")
    public ApiResponse<List<BusinessPlan>> businessPlans(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Integer year) {
        return ApiResponse.ok(businessPlanService.list(companyId, year), TraceIdUtil.get());
    }

    @PostMapping("/business-plans")
    @Audited(module = "plan", action = "create")
    public ApiResponse<BusinessPlan> createPlan(@RequestBody BusinessPlan plan) {
        return ApiResponse.ok(businessPlanService.create(plan), TraceIdUtil.get());
    }

    @PutMapping("/business-plans/{id}")
    @Audited(module = "plan", action = "update")
    public ApiResponse<BusinessPlan> updatePlan(@PathVariable Long id, @RequestBody BusinessPlan plan) {
        return ApiResponse.ok(businessPlanService.update(id, plan), TraceIdUtil.get());
    }

    @GetMapping("/business-plans/{id}/deviation")
    public ApiResponse<PlanDeviationResult> planDeviation(@PathVariable Long id) {
        return ApiResponse.ok(businessPlanService.evaluate(id), TraceIdUtil.get());
    }

    @PostMapping("/business-plans/scan-deviations")
    @Audited(module = "plan", action = "scan_deviations")
    public ApiResponse<List<PlanDeviationResult>> scanPlanDeviations() {
        return ApiResponse.ok(businessPlanService.scanDeviations(), TraceIdUtil.get());
    }

    // ---- 期初迁移 ----
    @GetMapping("/migrations/batches")
    public ApiResponse<List<MigrationBatch>> migrationBatches() {
        return ApiResponse.ok(migrationService.listBatches(), TraceIdUtil.get());
    }

    @PostMapping("/migrations/batches")
    @Audited(module = "migration", action = "create_batch")
    public ApiResponse<MigrationBatch> createBatch(@RequestBody Map<String, Object> body) {
        LocalDate cutoverDate = LocalDate.parse(body.get("cutoverDate").toString());
        return ApiResponse.ok(migrationService.createBatch(cutoverDate, (String) body.get("sourceFile")), TraceIdUtil.get());
    }

    @PostMapping("/migrations/batches/{batchId}/import")
    @Audited(module = "migration", action = "import_rows")
    public ApiResponse<Map<String, Object>> importRows(
            @PathVariable Long batchId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        return ApiResponse.ok(migrationService.importRows(batchId, (String) body.get("bizType"), rows),
                TraceIdUtil.get());
    }

    @PostMapping("/migrations/batches/{batchId}/init-lease-control")
    @Audited(module = "migration", action = "init_lease_control")
    public ApiResponse<Map<String, Object>> initLeaseControl(@PathVariable Long batchId) {
        return ApiResponse.ok(migrationService.initLeaseControl(batchId), TraceIdUtil.get());
    }

    @PostMapping("/migrations/batches/{batchId}/init-dunning")
    @Audited(module = "migration", action = "init_dunning")
    public ApiResponse<Integer> initDunning(@PathVariable Long batchId) {
        return ApiResponse.ok(migrationService.initDunning(batchId), TraceIdUtil.get());
    }

    @GetMapping("/migrations/batches/{batchId}/logs")
    public ApiResponse<List<MigrationImportLog>> migrationLogs(@PathVariable Long batchId) {
        return ApiResponse.ok(migrationService.listLogs(batchId), TraceIdUtil.get());
    }

    @PostMapping("/migrations/batches/{batchId}/reconcile")
    @Audited(module = "migration", action = "reconcile")
    public ApiResponse<MigrationBatch> reconcile(@PathVariable Long batchId) {
        return ApiResponse.ok(migrationService.reconcile(batchId), TraceIdUtil.get());
    }

    @PostMapping("/migrations/batches/{batchId}/lock")
    @Audited(module = "migration", action = "lock_cutover")
    public ApiResponse<MigrationBatch> lockCutover(@PathVariable Long batchId) {
        return ApiResponse.ok(migrationService.lockCutover(batchId), TraceIdUtil.get());
    }

    @PostMapping("/migrations/log")
    @Audited(module = "migration", action = "import_log")
    public ApiResponse<MigrationImportLog> importLog(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(migrationService.logImport(
                Long.valueOf(body.get("batchId").toString()),
                body.get("rowNo") == null ? null : Integer.parseInt(body.get("rowNo").toString()),
                (String) body.get("bizType"),
                (String) body.get("result"),
                (String) body.get("errorMsg"),
                body.get("refId") == null ? null : Long.valueOf(body.get("refId").toString())), TraceIdUtil.get());
    }
}
