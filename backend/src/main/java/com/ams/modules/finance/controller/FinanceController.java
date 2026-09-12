package com.ams.modules.finance.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.finance.entity.BankFlow;
import com.ams.modules.finance.entity.FinanceMonthClose;
import com.ams.modules.finance.entity.FinanceVoucher;
import com.ams.modules.finance.service.ReconcileService;
import com.ams.platform.security.Audited;
import java.util.List;
import java.util.Map;
import com.ams.platform.security.RequiresPerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    @RequiresPerm("finance.bankFlow:create")
    @Audited(module = "finance", action = "import_flow")
    public ApiResponse<BankFlow> importFlow(@RequestBody BankFlow flow) {
        return ApiResponse.ok(reconcileService.importFlow(flow), TraceIdUtil.get());
    }

    @GetMapping("/bank-flows/unmatched")
    @RequiresPerm("finance.bankFlow:view")
    public ApiResponse<List<BankFlow>> unmatched() {
        return ApiResponse.ok(reconcileService.unmatchedFlows(), TraceIdUtil.get());
    }

    @GetMapping("/payments/unmatched")
    @RequiresPerm("finance.bankFlow:view")
    public ApiResponse<List<Payment>> unmatchedPayments() {
        return ApiResponse.ok(reconcileService.unmatchedPayments(), TraceIdUtil.get());
    }

    @GetMapping("/reconcile/summary")
    @RequiresPerm("finance.bankFlow:view")
    public ApiResponse<Map<String, Object>> reconcileSummary() {
        return ApiResponse.ok(reconcileService.reconcileSummary(), TraceIdUtil.get());
    }

    @PostMapping("/bank-flows/rematch")
    @RequiresPerm("finance.bankFlow:update")
    @Audited(module = "finance", action = "rematch")
    public ApiResponse<Integer> rematch() {
        return ApiResponse.ok(reconcileService.rematchUnmatched(), TraceIdUtil.get());
    }

    @PostMapping("/bank-flows/{flowId}/match")
    @RequiresPerm("finance.bankFlow:update")
    @Audited(module = "finance", action = "manual_match")
    public ApiResponse<BankFlow> match(
            @PathVariable Long flowId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reconcileService.manualMatch(flowId,
                Long.valueOf(body.get("paymentId").toString())), TraceIdUtil.get());
    }

    @PostMapping("/vouchers")
    @RequiresPerm("finance.voucher:create")
    @Audited(module = "finance", action = "create_voucher")
    public ApiResponse<FinanceVoucher> createVoucher(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reconcileService.createVoucher(
                (String) body.get("bizType"),
                body.get("bizId") == null ? null : Long.valueOf(body.get("bizId").toString()),
                (String) body.get("contentJson")), TraceIdUtil.get());
    }

    @PostMapping("/vouchers/{id}/push")
    @RequiresPerm("finance.voucher:update")
    @Audited(module = "finance", action = "push_voucher")
    public ApiResponse<FinanceVoucher> pushVoucher(@PathVariable Long id) {
        return ApiResponse.ok(reconcileService.pushVoucher(id), TraceIdUtil.get());
    }

    @GetMapping("/vouchers")
    @RequiresPerm("finance.voucher:view")
    public ApiResponse<List<FinanceVoucher>> vouchers(@RequestParam(required = false) String status) {
        return ApiResponse.ok(reconcileService.listVouchers(status), TraceIdUtil.get());
    }

    @GetMapping("/month-closes")
    @RequiresPerm("finance.voucher:view")
    public ApiResponse<List<FinanceMonthClose>> monthCloses() {
        return ApiResponse.ok(reconcileService.listMonthCloses(), TraceIdUtil.get());
    }

    @PostMapping("/month-closes/{period}/lock")
    @RequiresPerm("finance.voucher:update")
    @Audited(module = "finance", action = "month_lock")
    public ApiResponse<FinanceMonthClose> lockMonth(
            @PathVariable String period, @RequestBody(required = false) Map<String, String> body) {
        String remark = body == null ? null : body.get("remark");
        return ApiResponse.ok(reconcileService.lockMonth(period, remark), TraceIdUtil.get());
    }

    @PostMapping("/month-closes/{period}/unlock")
    @RequiresPerm("finance.voucher:update")
    @Audited(module = "finance", action = "month_unlock")
    public ApiResponse<FinanceMonthClose> unlockMonth(
            @PathVariable String period, @RequestBody(required = false) Map<String, String> body) {
        String remark = body == null ? null : body.get("remark");
        return ApiResponse.ok(reconcileService.unlockMonth(period, remark), TraceIdUtil.get());
    }
}
