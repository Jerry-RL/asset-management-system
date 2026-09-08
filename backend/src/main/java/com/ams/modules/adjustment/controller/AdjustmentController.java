package com.ams.modules.adjustment.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.adjustment.entity.FeeReliefRequest;
import com.ams.modules.adjustment.entity.RentAdjustRequest;
import com.ams.modules.adjustment.service.FeeReliefService;
import com.ams.modules.adjustment.service.RentAdjustService;
import com.ams.platform.approval.entity.ApprovalInstance;
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
 * 费用减免与租金调价（FR-ADJ-*）。
 */
@RestController
@RequestMapping("/api/v1/adjustments")
public class AdjustmentController {

    private final FeeReliefService feeReliefService;
    private final RentAdjustService rentAdjustService;

    public AdjustmentController(FeeReliefService feeReliefService, RentAdjustService rentAdjustService) {
        this.feeReliefService = feeReliefService;
        this.rentAdjustService = rentAdjustService;
    }

    // ---- 减免 ----
    @GetMapping("/fee-reliefs")
    public ApiResponse<List<FeeReliefRequest>> listReliefs(
            @RequestParam(required = false) Long billId,
            @RequestParam(required = false) Long contractId) {
        return ApiResponse.ok(feeReliefService.list(billId, contractId), TraceIdUtil.get());
    }

    @GetMapping("/fee-reliefs/{id}")
    public ApiResponse<FeeReliefRequest> getRelief(@PathVariable Long id) {
        return ApiResponse.ok(feeReliefService.get(id), TraceIdUtil.get());
    }

    @PostMapping("/fee-reliefs")
    @Audited(module = "adjustment", action = "fee_relief_apply")
    public ApiResponse<FeeReliefRequest> applyRelief(@RequestBody Map<String, Object> body) {
        Long billId = Long.valueOf(body.get("billId").toString());
        BigDecimal amount = new BigDecimal(body.get("reliefAmount").toString());
        return ApiResponse.ok(feeReliefService.apply(billId, amount,
                (String) body.get("reason"), (String) body.get("fileIds")), TraceIdUtil.get());
    }

    @PostMapping("/fee-reliefs/{id}/submit")
    @Audited(module = "adjustment", action = "fee_relief_submit")
    public ApiResponse<ApprovalInstance> submitRelief(@PathVariable Long id) {
        return ApiResponse.ok(feeReliefService.submit(id), TraceIdUtil.get());
    }

    // ---- 调价 ----
    @GetMapping("/rent-adjusts")
    public ApiResponse<List<RentAdjustRequest>> listAdjusts(@RequestParam(required = false) Long contractId) {
        return ApiResponse.ok(rentAdjustService.list(contractId), TraceIdUtil.get());
    }

    @GetMapping("/rent-adjusts/{id}")
    public ApiResponse<RentAdjustRequest> getAdjust(@PathVariable Long id) {
        return ApiResponse.ok(rentAdjustService.get(id), TraceIdUtil.get());
    }

    @PostMapping("/rent-adjusts")
    @Audited(module = "adjustment", action = "rent_adjust_apply")
    public ApiResponse<RentAdjustRequest> applyAdjust(@RequestBody Map<String, Object> body) {
        Long contractId = Long.valueOf(body.get("contractId").toString());
        BigDecimal newRent = new BigDecimal(body.get("newRentAmount").toString());
        LocalDate effective = body.get("effectiveDate") == null
                ? null
                : LocalDate.parse(body.get("effectiveDate").toString());
        return ApiResponse.ok(rentAdjustService.apply(
                contractId, newRent, effective,
                (String) body.get("issuedStrategy"),
                (String) body.get("reason"),
                (String) body.get("fileIds")), TraceIdUtil.get());
    }

    @PostMapping("/rent-adjusts/{id}/submit")
    @Audited(module = "adjustment", action = "rent_adjust_submit")
    public ApiResponse<ApprovalInstance> submitAdjust(@PathVariable Long id) {
        return ApiResponse.ok(rentAdjustService.submit(id), TraceIdUtil.get());
    }
}
