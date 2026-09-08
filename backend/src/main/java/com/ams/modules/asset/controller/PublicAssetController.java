package com.ams.modules.asset.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.asset.service.AssetQrService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开扫码查资产（免登录），供手机扫描二维码查看招租/基础信息（FR-MPU-007）。
 */
@RestController
@RequestMapping("/api/v1/public/assets")
public class PublicAssetController {

    private final AssetQrService assetQrService;

    public PublicAssetController(AssetQrService assetQrService) {
        this.assetQrService = assetQrService;
    }

    @GetMapping("/{assetId}/scan")
    public ApiResponse<Map<String, Object>> scan(@PathVariable Long assetId) {
        return ApiResponse.ok(assetQrService.publicScanView(assetId), TraceIdUtil.get());
    }
}
