package com.ams.modules.fixedasset.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.fixedasset.entity.FaInventoryItem;
import com.ams.modules.fixedasset.entity.FaInventoryPlan;
import com.ams.modules.fixedasset.entity.FixedAsset;
import com.ams.modules.fixedasset.service.FixedAssetService;
import com.ams.platform.security.Audited;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * 固定资产接口（FR-FA-001~004）：台账、状态流转、盘点。
 */
@RestController
@RequestMapping("/api/v1/fixed-assets")
public class FixedAssetController {

    private final FixedAssetService fixedAssetService;

    public FixedAssetController(FixedAssetService fixedAssetService) {
        this.fixedAssetService = fixedAssetService;
    }

    @GetMapping
    public ApiResponse<PageResult<FixedAsset>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(fixedAssetService.page(page, pageSize, keyword, status, companyId),
                TraceIdUtil.get());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(fixedAssetService.summary(companyId), TraceIdUtil.get());
    }

    @GetMapping("/inventories")
    public ApiResponse<List<FaInventoryPlan>> inventories(@RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(fixedAssetService.listPlans(companyId), TraceIdUtil.get());
    }

    @PostMapping("/inventories")
    @Audited(module = "fixed_asset", action = "create_inventory")
    public ApiResponse<FaInventoryPlan> createInventory(@RequestBody Map<String, Object> body) {
        Long companyId = body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString());
        LocalDate date = body.get("plannedDate") == null ? null
                : LocalDate.parse(body.get("plannedDate").toString());
        return ApiResponse.ok(fixedAssetService.createPlan(companyId, (String) body.get("title"), date),
                TraceIdUtil.get());
    }

    @PostMapping("/inventories/{planId}/start")
    @Audited(module = "fixed_asset", action = "start_inventory")
    public ApiResponse<FaInventoryPlan> startInventory(@PathVariable Long planId) {
        return ApiResponse.ok(fixedAssetService.startCounting(planId), TraceIdUtil.get());
    }

    @GetMapping("/inventories/{planId}/items")
    public ApiResponse<List<FaInventoryItem>> inventoryItems(@PathVariable Long planId) {
        return ApiResponse.ok(fixedAssetService.listItems(planId), TraceIdUtil.get());
    }

    @PostMapping("/inventories/{planId}/scan")
    @Audited(module = "fixed_asset", action = "scan_inventory")
    public ApiResponse<FaInventoryItem> scan(@PathVariable Long planId, @RequestBody Map<String, Object> body) {
        Long faId = Long.valueOf(body.get("fixedAssetId").toString());
        return ApiResponse.ok(fixedAssetService.scan(planId, faId,
                (String) body.get("actualStatus"), (String) body.get("remark")), TraceIdUtil.get());
    }

    @PostMapping("/inventories/{planId}/close")
    @Audited(module = "fixed_asset", action = "close_inventory")
    public ApiResponse<FaInventoryPlan> closeInventory(@PathVariable Long planId) {
        return ApiResponse.ok(fixedAssetService.close(planId), TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    public ApiResponse<FixedAsset> get(@PathVariable Long id) {
        return ApiResponse.ok(fixedAssetService.get(id), TraceIdUtil.get());
    }

    @PostMapping
    @Audited(module = "fixed_asset", action = "create")
    public ApiResponse<FixedAsset> create(@RequestBody FixedAsset asset) {
        return ApiResponse.ok(fixedAssetService.create(asset), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    @Audited(module = "fixed_asset", action = "update")
    public ApiResponse<FixedAsset> update(@PathVariable Long id, @RequestBody FixedAsset asset) {
        return ApiResponse.ok(fixedAssetService.update(id, asset), TraceIdUtil.get());
    }

    @PostMapping("/{id}/transition")
    @Audited(module = "fixed_asset", action = "transition")
    public ApiResponse<FixedAsset> transition(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(fixedAssetService.transition(id, body.get("status"), body.get("userName")),
                TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    @Audited(module = "fixed_asset", action = "delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        fixedAssetService.delete(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
}
