package com.ams.modules.asset.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.asset.entity.AssetCertificate;
import com.ams.modules.asset.entity.AssetTransfer;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.asset.service.TransferService;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.security.Audited;
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
 * 资债权证 / 抵押 / 调拨接口（FR-CERT-001/002、FR-MORT-*）。
 */
@RestController
@RequestMapping("/api/v1")
public class CertificateController {

    private final CertificateService certificateService;
    private final TransferService transferService;

    public CertificateController(CertificateService certificateService, TransferService transferService) {
        this.certificateService = certificateService;
        this.transferService = transferService;
    }

    @GetMapping("/certificates")
    public ApiResponse<List<AssetCertificate>> allCertificates() {
        return ApiResponse.ok(certificateService.listCertificates(null), TraceIdUtil.get());
    }

    @GetMapping("/mortgages")
    public ApiResponse<List<Mortgage>> allMortgages() {
        return ApiResponse.ok(certificateService.listMortgages(null), TraceIdUtil.get());
    }

    @GetMapping("/assets/{assetId}/certificates")
    public ApiResponse<List<AssetCertificate>> certificates(@PathVariable Long assetId) {
        return ApiResponse.ok(certificateService.listCertificates(assetId), TraceIdUtil.get());
    }

    @PostMapping("/assets/{assetId}/certificates")
    @Audited(module = "certificate", action = "create_certificate")
    public ApiResponse<AssetCertificate> createCertificate(
            @PathVariable Long assetId, @RequestBody AssetCertificate certificate) {
        certificate.setAssetId(assetId);
        return ApiResponse.ok(certificateService.createCertificate(certificate), TraceIdUtil.get());
    }

    @GetMapping("/assets/{assetId}/mortgages")
    public ApiResponse<List<Mortgage>> mortgages(@PathVariable Long assetId) {
        return ApiResponse.ok(certificateService.listMortgages(assetId), TraceIdUtil.get());
    }

    @PostMapping("/assets/{assetId}/mortgages")
    @Audited(module = "certificate", action = "register_mortgage")
    public ApiResponse<Mortgage> registerMortgage(
            @PathVariable Long assetId, @RequestBody Mortgage mortgage) {
        mortgage.setAssetId(assetId);
        return ApiResponse.ok(certificateService.registerMortgage(mortgage), TraceIdUtil.get());
    }

    @PostMapping("/mortgages/{id}/release")
    @Audited(module = "certificate", action = "release_mortgage")
    public ApiResponse<Mortgage> releaseMortgage(@PathVariable Long id) {
        return ApiResponse.ok(certificateService.releaseMortgage(id), TraceIdUtil.get());
    }

    @PostMapping("/mortgages/{id}/release/submit")
    @Audited(module = "certificate", action = "submit_release")
    public ApiResponse<ApprovalInstance> submitRelease(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String remark = body == null ? null : body.get("remark");
        return ApiResponse.ok(certificateService.submitRelease(id, remark), TraceIdUtil.get());
    }

    @GetMapping("/mortgages/expiring")
    public ApiResponse<List<Mortgage>> expiring(@RequestParam(defaultValue = "30") int withinDays) {
        return ApiResponse.ok(certificateService.listExpiring(withinDays), TraceIdUtil.get());
    }

    @GetMapping("/asset-transfers")
    public ApiResponse<List<AssetTransfer>> transfers(@RequestParam(required = false) Long assetId) {
        return ApiResponse.ok(transferService.list(assetId), TraceIdUtil.get());
    }

    @PostMapping("/asset-transfers")
    @Audited(module = "certificate", action = "create_transfer")
    public ApiResponse<AssetTransfer> createTransfer(@RequestBody AssetTransfer transfer) {
        return ApiResponse.ok(transferService.create(transfer), TraceIdUtil.get());
    }

    @PostMapping("/asset-transfers/{id}/approve")
    @Audited(module = "certificate", action = "approve_transfer")
    public ApiResponse<AssetTransfer> approveTransfer(@PathVariable Long id) {
        return ApiResponse.ok(transferService.approve(id), TraceIdUtil.get());
    }

    @GetMapping("/asset-transfers/{id}")
    public ApiResponse<AssetTransfer> transfer(@PathVariable Long id) {
        return ApiResponse.ok(transferService.get(id), TraceIdUtil.get());
    }
}
