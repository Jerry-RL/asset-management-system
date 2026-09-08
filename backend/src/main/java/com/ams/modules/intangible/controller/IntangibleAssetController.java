package com.ams.modules.intangible.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.intangible.entity.IntangibleAsset;
import com.ams.modules.intangible.service.IntangibleAssetService;
import com.ams.platform.security.Audited;
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
 * 无形资产接口（FR-IA-001~004）。
 */
@RestController
@RequestMapping("/api/v1/intangible-assets")
public class IntangibleAssetController {

    private final IntangibleAssetService intangibleAssetService;

    public IntangibleAssetController(IntangibleAssetService intangibleAssetService) {
        this.intangibleAssetService = intangibleAssetService;
    }

    @GetMapping
    public ApiResponse<PageResult<IntangibleAsset>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String rightsType) {
        return ApiResponse.ok(intangibleAssetService.page(page, pageSize, keyword, rightsType),
                TraceIdUtil.get());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(intangibleAssetService.summary(), TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    public ApiResponse<IntangibleAsset> get(@PathVariable Long id) {
        return ApiResponse.ok(intangibleAssetService.get(id), TraceIdUtil.get());
    }

    @PostMapping
    @Audited(module = "intangible", action = "create")
    public ApiResponse<IntangibleAsset> create(@RequestBody IntangibleAsset asset) {
        return ApiResponse.ok(intangibleAssetService.create(asset), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    @Audited(module = "intangible", action = "update")
    public ApiResponse<IntangibleAsset> update(@PathVariable Long id, @RequestBody IntangibleAsset asset) {
        return ApiResponse.ok(intangibleAssetService.update(id, asset), TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    @Audited(module = "intangible", action = "delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        intangibleAssetService.delete(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/amortize")
    @Audited(module = "intangible", action = "amortize")
    public ApiResponse<Map<String, Object>> amortize() {
        int n = intangibleAssetService.amortizeMonthly();
        return ApiResponse.ok(Map.of("amortized", n), TraceIdUtil.get());
    }
}
