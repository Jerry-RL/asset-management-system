package com.ams.modules.intangible.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.intangible.entity.IntangibleAsset;
import com.ams.modules.intangible.mapper.IntangibleAssetMapper;
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
 * 无形资产接口（FR-IA-001~004）：台账、评估摊销、到期预警基础。
 */
@RestController
@RequestMapping("/api/v1/intangible-assets")
public class IntangibleAssetController {

    private final IntangibleAssetMapper mapper;

    public IntangibleAssetController(IntangibleAssetMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<PageResult<IntangibleAsset>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String rightsType) {
        Page<IntangibleAsset> result = mapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<IntangibleAsset>()
                        .eq(rightsType != null, IntangibleAsset::getRightsType, rightsType)
                        .like(keyword != null && !keyword.isBlank(), IntangibleAsset::getName, keyword)
                        .orderByDesc(IntangibleAsset::getId));
        return ApiResponse.ok(PageResult.of(result.getRecords(), result.getTotal(), page, pageSize),
                TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    public ApiResponse<IntangibleAsset> get(@PathVariable Long id) {
        return ApiResponse.ok(mapper.selectById(id), TraceIdUtil.get());
    }

    @PostMapping
    public ApiResponse<IntangibleAsset> create(@RequestBody IntangibleAsset asset) {
        mapper.insert(asset);
        return ApiResponse.ok(asset, TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    public ApiResponse<IntangibleAsset> update(@PathVariable Long id, @RequestBody IntangibleAsset asset) {
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
