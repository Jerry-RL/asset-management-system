package com.ams.modules.lease.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.entity.TenantCreditLog;
import com.ams.modules.lease.service.LeaseListingService;
import com.ams.modules.lease.service.TenantService;
import com.ams.platform.security.Audited;
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
 * 租户 / 招租 / 公开招租接口（FR-OP-001、FR-LEASE-*、FR-TENDER-*）。
 */
@RestController
@RequestMapping("/api/v1")
public class LeaseController {

    private final TenantService tenantService;
    private final LeaseListingService listingService;

    public LeaseController(TenantService tenantService, LeaseListingService listingService) {
        this.tenantService = tenantService;
        this.listingService = listingService;
    }

    // ---- 租户 ----
    @GetMapping("/tenants")
    public ApiResponse<PageResult<Tenant>> tenants(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean blacklist) {
        return ApiResponse.ok(tenantService.page(page, pageSize, keyword, blacklist), TraceIdUtil.get());
    }

    @GetMapping("/tenants/{id}")
    public ApiResponse<Tenant> tenant(@PathVariable Long id) {
        return ApiResponse.ok(tenantService.get(id), TraceIdUtil.get());
    }

    @PostMapping("/tenants")
    @Audited(module = "tenant", action = "create")
    public ApiResponse<Tenant> createTenant(@RequestBody Tenant tenant) {
        return ApiResponse.ok(tenantService.create(tenant), TraceIdUtil.get());
    }

    @PutMapping("/tenants/{id}")
    @Audited(module = "tenant", action = "update")
    public ApiResponse<Tenant> updateTenant(@PathVariable Long id, @RequestBody Tenant tenant) {
        return ApiResponse.ok(tenantService.update(id, tenant), TraceIdUtil.get());
    }

    @PostMapping("/tenants/{id}/credit")
    @Audited(module = "tenant", action = "adjust_credit")
    public ApiResponse<Void> adjustCredit(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        tenantService.adjustCredit(id,
                ((Number) body.getOrDefault("scoreDelta", 0)).intValue(),
                (String) body.get("eventType"),
                (String) body.get("remark"));
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/tenants/{id}/blacklist")
    @Audited(module = "tenant", action = "set_blacklist")
    public ApiResponse<Void> setBlacklist(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        tenantService.setBlacklist(id, Boolean.TRUE.equals(body.get("blacklist")));
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @GetMapping("/tenants/{id}/credit-logs")
    public ApiResponse<List<TenantCreditLog>> creditLogs(@PathVariable Long id) {
        return ApiResponse.ok(tenantService.creditLogs(id), TraceIdUtil.get());
    }

    // ---- 招租 ----
    @GetMapping("/lease-listings")
    public ApiResponse<List<LeaseListing>> listings(@RequestParam(required = false) String status) {
        return ApiResponse.ok(listingService.listListings(status), TraceIdUtil.get());
    }

    @PostMapping("/lease-listings")
    @Audited(module = "lease", action = "publish")
    public ApiResponse<LeaseListing> publish(@RequestBody LeaseListing listing) {
        return ApiResponse.ok(listingService.publish(listing), TraceIdUtil.get());
    }

    @PostMapping("/lease-listings/{id}/close")
    @Audited(module = "lease", action = "close")
    public ApiResponse<LeaseListing> closeListing(@PathVariable Long id) {
        return ApiResponse.ok(listingService.close(id), TraceIdUtil.get());
    }
}
