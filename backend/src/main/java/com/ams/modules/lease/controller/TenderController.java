package com.ams.modules.lease.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.lease.entity.TenderAnnouncement;
import com.ams.modules.lease.entity.TenderApplication;
import com.ams.modules.lease.service.LeaseListingService;
import com.ams.platform.security.Audited;
import java.math.BigDecimal;
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
 * 公开招租接口（FR-TENDER-*）。
 */
@RestController
@RequestMapping("/api/v1/tender")
public class TenderController {

    private final LeaseListingService listingService;

    public TenderController(LeaseListingService listingService) {
        this.listingService = listingService;
    }

    @PostMapping("/announcements")
    @Audited(module = "tender", action = "create_announcement")
    public ApiResponse<TenderAnnouncement> createAnnouncement(@RequestBody TenderAnnouncement announcement) {
        return ApiResponse.ok(listingService.createAnnouncement(announcement), TraceIdUtil.get());
    }

    @GetMapping("/announcements")
    public ApiResponse<List<TenderAnnouncement>> announcements(@RequestParam(required = false) String status) {
        return ApiResponse.ok(listingService.listAnnouncements(status), TraceIdUtil.get());
    }

    @GetMapping("/announcements/{id}/applications")
    public ApiResponse<List<TenderApplication>> applications(@PathVariable Long id) {
        return ApiResponse.ok(listingService.listApplications(id), TraceIdUtil.get());
    }

    @PostMapping("/announcements/{id}/applications")
    @Audited(module = "tender", action = "apply")
    public ApiResponse<TenderApplication> apply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = Long.valueOf(body.get("tenantId").toString());
        Boolean depositPaid = body.get("depositPaid") != null && Boolean.parseBoolean(body.get("depositPaid").toString());
        BigDecimal depositAmount = body.get("depositAmount") == null ? null
                : new BigDecimal(body.get("depositAmount").toString());
        Long materialsFileId = body.get("materialsFileId") == null ? null
                : Long.valueOf(body.get("materialsFileId").toString());
        return ApiResponse.ok(listingService.apply(id, tenantId, depositPaid, depositAmount, materialsFileId),
                TraceIdUtil.get());
    }

    @PostMapping("/applications/{id}/audit")
    @Audited(module = "tender", action = "audit")
    public ApiResponse<TenderApplication> audit(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.parseBoolean(body.get("approved").toString());
        String comment = (String) body.get("comment");
        Integer rankNo = body.get("rankNo") == null ? null : Integer.parseInt(body.get("rankNo").toString());
        return ApiResponse.ok(listingService.audit(id, approved, comment, rankNo), TraceIdUtil.get());
    }

    @PostMapping("/announcements/{id}/flow")
    @Audited(module = "tender", action = "mark_flowed")
    public ApiResponse<TenderAnnouncement> markFlowed(@PathVariable Long id) {
        return ApiResponse.ok(listingService.markFlowed(id), TraceIdUtil.get());
    }

    @PostMapping("/announcements/{id}/finalize")
    @Audited(module = "tender", action = "finalize")
    public ApiResponse<TenderAnnouncement> finalizeResult(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long winnerApplicationId = Long.valueOf(body.get("winnerApplicationId").toString());
        return ApiResponse.ok(listingService.finalizeResult(id, winnerApplicationId), TraceIdUtil.get());
    }

    @PostMapping("/applications/{id}/refund-deposit")
    @Audited(module = "tender", action = "refund_deposit")
    public ApiResponse<TenderApplication> refundDeposit(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String remark = body == null ? null : body.get("remark");
        return ApiResponse.ok(listingService.refundDeposit(id, remark), TraceIdUtil.get());
    }

    @GetMapping("/announcements/{id}/filing-package")
    public ApiResponse<Map<String, Object>> filingPackage(@PathVariable Long id) {
        return ApiResponse.ok(listingService.getFilingPackage(id), TraceIdUtil.get());
    }
}
