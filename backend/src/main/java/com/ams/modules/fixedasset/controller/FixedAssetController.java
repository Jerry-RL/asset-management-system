package com.ams.modules.fixedasset.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.fixedasset.entity.FixedAsset;
import com.ams.modules.fixedasset.mapper.FixedAssetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
 * 固定资产接口（FR-FA-001~006）：固资清单、管理、盘点基础。
 */
@RestController
@RequestMapping("/api/v1/fixed-assets")
public class FixedAssetController {

    private final FixedAssetMapper mapper;

    public FixedAssetController(FixedAssetMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<PageResult<FixedAsset>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long companyId) {
        Page<FixedAsset> result = mapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<FixedAsset>()
                        .eq(status != null, FixedAsset::getStatus, status)
                        .eq(companyId != null, FixedAsset::getCompanyId, companyId)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(FixedAsset::getName, keyword)
                                        .or().like(FixedAsset::getAssetNo, keyword))
                        .orderByDesc(FixedAsset::getId));
        return ApiResponse.ok(PageResult.of(result.getRecords(), result.getTotal(), page, pageSize),
                TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    public ApiResponse<FixedAsset> get(@PathVariable Long id) {
        return ApiResponse.ok(mapper.selectById(id), TraceIdUtil.get());
    }

    @PostMapping
    public ApiResponse<FixedAsset> create(@RequestBody FixedAsset asset) {
        mapper.insert(asset);
        return ApiResponse.ok(asset, TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    public ApiResponse<FixedAsset> update(@PathVariable Long id, @RequestBody FixedAsset asset) {
        asset.setId(id);
        mapper.updateById(asset);
        return ApiResponse.ok(mapper.selectById(id), TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
}
