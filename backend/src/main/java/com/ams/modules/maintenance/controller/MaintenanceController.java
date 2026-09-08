package com.ams.modules.maintenance.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.maintenance.entity.InspectionRecord;
import com.ams.modules.maintenance.entity.MaintenanceVendor;
import com.ams.modules.maintenance.entity.RepairOrder;
import com.ams.modules.maintenance.service.RepairService;
import com.ams.platform.security.Audited;
import java.time.LocalDateTime;
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
 * 巡检维修接口（FR-MNT-*）。
 */
@RestController
@RequestMapping("/api/v1")
public class MaintenanceController {

    private final RepairService repairService;

    public MaintenanceController(RepairService repairService) {
        this.repairService = repairService;
    }

    @GetMapping("/repairs")
    public ApiResponse<PageResult<RepairOrder>> repairs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(repairService.page(page, pageSize, status, keyword), TraceIdUtil.get());
    }

    @PostMapping("/repairs")
    @Audited(module = "maintenance", action = "create_repair")
    public ApiResponse<RepairOrder> createRepair(@RequestBody RepairOrder order) {
        return ApiResponse.ok(repairService.create(order), TraceIdUtil.get());
    }

    @PostMapping("/repairs/{repairId}/dispatch")
    @Audited(module = "maintenance", action = "dispatch")
    public ApiResponse<RepairOrder> dispatch(@PathVariable Long repairId, @RequestBody Map<String, Object> body) {
        Long vendorId = body.get("vendorId") == null ? null : Long.valueOf(body.get("vendorId").toString());
        Long assigneeId = body.get("assigneeId") == null ? null : Long.valueOf(body.get("assigneeId").toString());
        return ApiResponse.ok(repairService.dispatch(repairId, vendorId, assigneeId, null, null), TraceIdUtil.get());
    }

    @PostMapping("/repairs/{repairId}/complete")
    @Audited(module = "maintenance", action = "complete_repair")
    public ApiResponse<RepairOrder> complete(@PathVariable Long repairId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(repairService.complete(repairId, (String) body.get("resultRemark")), TraceIdUtil.get());
    }

    @PostMapping("/repairs/{repairId}/accept")
    @Audited(module = "maintenance", action = "accept_repair")
    public ApiResponse<RepairOrder> accept(@PathVariable Long repairId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(repairService.accept(repairId,
                Boolean.parseBoolean(body.get("accepted").toString())), TraceIdUtil.get());
    }

    @GetMapping("/vendors")
    public ApiResponse<List<MaintenanceVendor>> vendors() {
        return ApiResponse.ok(repairService.listVendors(), TraceIdUtil.get());
    }

    @PostMapping("/vendors")
    @Audited(module = "maintenance", action = "create_vendor")
    public ApiResponse<MaintenanceVendor> createVendor(@RequestBody MaintenanceVendor vendor) {
        return ApiResponse.ok(repairService.createVendor(vendor), TraceIdUtil.get());
    }

    @GetMapping("/inspections")
    public ApiResponse<List<InspectionRecord>> inspections(@RequestParam(required = false) Long assetId) {
        return ApiResponse.ok(repairService.listInspections(assetId), TraceIdUtil.get());
    }

    @PostMapping("/inspections")
    @Audited(module = "maintenance", action = "create_inspection")
    public ApiResponse<InspectionRecord> createInspection(@RequestBody InspectionRecord record) {
        return ApiResponse.ok(repairService.createInspection(record), TraceIdUtil.get());
    }
}
