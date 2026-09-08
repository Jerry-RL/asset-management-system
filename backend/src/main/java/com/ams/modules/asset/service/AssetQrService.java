package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.config.AmsProperties;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一产一码：为每条资产生成唯一扫码链接与 PNG 二维码（FR-AST / FR-MPU-007）。
 */
@Service
public class AssetQrService {

    private static final int DEFAULT_SIZE = 512;

    private final AssetMapper assetMapper;
    private final LeaseListingMapper listingMapper;
    private final AmsProperties amsProperties;

    public AssetQrService(
            AssetMapper assetMapper, LeaseListingMapper listingMapper, AmsProperties amsProperties) {
        this.assetMapper = assetMapper;
        this.listingMapper = listingMapper;
        this.amsProperties = amsProperties;
    }

    /** 扫码落地页 URL，写入 asset.qr_code_url。 */
    public String buildScanUrl(Long assetId) {
        String base = amsProperties.getQr().getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            base = "http://localhost:5174";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/scan/" + assetId;
    }

    /** 新建/拆分/合并后绑定二维码链接（幂等）。 */
    @Transactional
    public Asset ensureQrCode(Asset asset) {
        if (asset == null || asset.getId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产无效");
        }
        String expected = buildScanUrl(asset.getId());
        if (expected.equals(asset.getQrCodeUrl())) {
            return asset;
        }
        asset.setQrCodeUrl(expected);
        assetMapper.updateById(asset);
        return asset;
    }

    public Asset ensureQrCode(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return ensureQrCode(asset);
    }

    /** 生成 PNG 字节流，供下载张贴。 */
    public byte[] generatePng(Long assetId, int size) {
        Asset asset = ensureQrCode(assetId);
        int pixels = size > 0 ? size : DEFAULT_SIZE;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter()
                    .encode(asset.getQrCodeUrl(), BarcodeFormat.QR_CODE, pixels, pixels, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "二维码生成失败: " + e.getMessage());
        }
    }

    /** 公开扫码查询：仅返回可公示的招租/台账字段。 */
    public Map<String, Object> publicScanView(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || (asset.getStructureStatus() != null
                && "merged_out".equals(asset.getStructureStatus()))) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在或已合并退出");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", asset.getId());
        view.put("assetNo", asset.getAssetNo());
        view.put("name", asset.getName());
        view.put("assetType", asset.getAssetType());
        view.put("area", asset.getArea());
        view.put("leaseControlStatus", asset.getLeaseControlStatus());
        view.put("province", asset.getProvince());
        view.put("city", asset.getCity());
        view.put("district", asset.getDistrict());
        view.put("address", asset.getAddress());
        view.put("baseRentAssessed", asset.getBaseRentAssessed());
        view.put("usageType", asset.getUsageType());
        view.put("structureType", asset.getStructureType());
        view.put("qrCodeUrl", asset.getQrCodeUrl() != null ? asset.getQrCodeUrl() : buildScanUrl(assetId));

        LeaseListing listing = listingMapper.selectOne(
                new LambdaQueryWrapper<LeaseListing>()
                        .eq(LeaseListing::getAssetId, assetId)
                        .eq(LeaseListing::getStatus, "active")
                        .orderByDesc(LeaseListing::getId)
                        .last("LIMIT 1"));
        if (listing != null) {
            Map<String, Object> listingView = new LinkedHashMap<>();
            listingView.put("id", listing.getId());
            listingView.put("rentAmount", listing.getRentAmount());
            listingView.put("rentNegotiable", listing.getRentNegotiable());
            listingView.put("status", listing.getStatus());
            listingView.put("remark", listing.getRemark());
            listingView.put("publishedAt", listing.getPublishedAt());
            view.put("listing", listingView);
        } else {
            view.put("listing", null);
        }
        return view;
    }

    /**
     * 从扫码内容解析资产：支持资产编号、数字 ID、完整扫码 URL、qr_code_url 原值。
     */
    public Asset resolveByScanContent(String scanCode) {
        if (scanCode == null || scanCode.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "扫码内容为空");
        }
        String code = scanCode.trim();

        Asset byNo = assetMapper.selectOne(
                new LambdaQueryWrapper<Asset>().eq(Asset::getAssetNo, code).last("LIMIT 1"));
        if (byNo != null) {
            return byNo;
        }

        Asset byQr = assetMapper.selectOne(
                new LambdaQueryWrapper<Asset>().eq(Asset::getQrCodeUrl, code).last("LIMIT 1"));
        if (byQr != null) {
            return byQr;
        }

        Long idFromUrl = extractAssetIdFromScanUrl(code);
        if (idFromUrl != null) {
            Asset byUrl = assetMapper.selectById(idFromUrl);
            if (byUrl != null) {
                return byUrl;
            }
        }

        try {
            Long id = Long.valueOf(code);
            Asset byId = assetMapper.selectById(id);
            if (byId != null) {
                return byId;
            }
        } catch (NumberFormatException ignored) {
            // ignore
        }
        throw new AppException(ErrorCode.NOT_FOUND, "未找到对应资产: " + code);
    }

    static Long extractAssetIdFromScanUrl(String text) {
        // .../scan/123 或 .../scan/123?...
        int idx = text.lastIndexOf("/scan/");
        if (idx < 0) {
            return null;
        }
        String rest = text.substring(idx + "/scan/".length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (!Character.isDigit(c)) {
                end = i;
                break;
            }
        }
        if (end == 0) {
            return null;
        }
        try {
            return Long.valueOf(rest.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
