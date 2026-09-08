package com.ams.modules.finance.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.finance.entity.BankFlow;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.platform.security.Audited;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业财对账接口（FR-FIN-*）。
 */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {

    private final ReconcileService reconcileService;

    public FinanceController(ReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    @PostMapping("/bank-flows")
    @Audited(module = "finance", action = "import_flow")
    public ApiResponse<BankFlow> importFlow(@RequestBody BankFlow flow) {
        return ApiResponse.ok(reconcileService.importFlow(flow), TraceIdUtil.get());
    }

    @GetMapping("/bank-flows/unmatched")
    public ApiResponse<List<BankFlow>> unmatched() {
        return ApiResponse.ok(reconcileService.unmatchedFlows(), TraceIdUtil.get());
    }

    @PostMapping("/bank-flows/{flowId}/match")
    @Audited(module = "finance", action = "manual_match")
    public ApiResponse<BankFlow> match(
            @org.springframework.web.bind.annotation.PathVariable Long flowId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reconcileService.manualMatch(flowId,
                Long.valueOf(body.get("paymentId").toString())), TraceIdUtil.get());
    }

    @PostMapping("/vouchers")
    @Audited(module = "finance", action = "create_voucher")
    public ApiResponse<FinanceVoucher> createVoucher(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reconcileService.createVoucher(
                (String) body.get("bizType"),
                body.get("bizId") == null ? null : Long.valueOf(body.get("bizId").toString()),
                (String) body.get("contentJson")), TraceIdUtil.get());
    }

    @GetMapping("/vouchers")
    public ApiResponse<List<FinanceVoucher>> vouchers(@RequestParam(required = false) String status) {
        return ApiResponse.ok(reconcileService.listVouchers(status), TraceIdUtil.get());
    }
}
