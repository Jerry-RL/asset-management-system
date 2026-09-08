package com.ams.modules.lease.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.lease.entity.LeaseBundle;
import com.ams.modules.lease.service.LeaseBundleService;
import com.ams.platform.security.Audited;
import java.math.BigDecimal;
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
 * 组合/拆分租赁（FR-OPS-006）。
 */
@RestController
@RequestMapping("/api/v1/lease-bundles")
public class LeaseBundleController {

    private final LeaseBundleService leaseBundleService;

    public LeaseBundleController(LeaseBundleService leaseBundleService) {
        this.leaseBundleService = leaseBundleService;
    }

    @GetMapping
    public ApiResponse<List<LeaseBundle>> list(@RequestParam(required = false) String bundleType) {
        return ApiResponse.ok(leaseBundleService.list(bundleType), TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(leaseBundleService.detail(id), TraceIdUtil.get());
    }

    @PostMapping("/combo")
    @Audited(module = "lease", action = "combo")
    public ApiResponse<Map<String, Object>> combo(@RequestBody Map<String, Object> body) {
        Long tenantId = Long.valueOf(body.get("tenantId").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assets = (List<Map<String, Object>>) body.get("assets");
        BigDecimal totalRent = body.get("totalRent") == null ? null : new BigDecimal(body.get("totalRent").toString());
        LocalDate start = body.get("startDate") == null ? null : LocalDate.parse(body.get("startDate").toString());
        LocalDate end = body.get("endDate") == null ? null : LocalDate.parse(body.get("endDate").toString());
        return ApiResponse.ok(leaseBundleService.createCombo(
                tenantId, assets, totalRent, (String) body.get("rentMode"), start, end, (String) body.get("remark")),
                TraceIdUtil.get());
    }

    @PostMapping("/split")
    @Audited(module = "lease", action = "split")
    public ApiResponse<Map<String, Object>> split(@RequestBody Map<String, Object> body) {
        Long assetId = Long.valueOf(body.get("assetId").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tenants = (List<Map<String, Object>>) body.get("tenants");
        LocalDate start = body.get("startDate") == null ? null : LocalDate.parse(body.get("startDate").toString());
        LocalDate end = body.get("endDate") == null ? null : LocalDate.parse(body.get("endDate").toString());
        return ApiResponse.ok(leaseBundleService.createSplit(
                assetId, tenants, (String) body.get("rentMode"), start, end, (String) body.get("remark")),
                TraceIdUtil.get());
    }
}
