package com.ams.modules.billing.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.entity.BillPayment;
import com.ams.modules.billing.entity.Payment;
import com.ams.modules.billing.entity.Prepay;
import com.ams.modules.billing.entity.RefundOrder;
import com.ams.modules.billing.service.AllocationService;
import com.ams.modules.billing.service.BillService;
import com.ams.modules.billing.service.PaymentService;
import com.ams.modules.billing.service.PrepayService;
import com.ams.modules.billing.service.RefundService;
import com.ams.modules.pricing.entity.PaymentPlan;
import com.ams.modules.pricing.mapper.PaymentPlanMapper;
import com.ams.platform.security.Audited;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * 收费大厅 / 账单 / 收款 / 退款接口（FR-BILL-*、FR-PAY-*、FR-REF-*）。
 */
@RestController
@RequestMapping("/api/v1")
public class BillingController {

    private final BillService billService;
    private final PaymentService paymentService;
    private final AllocationService allocationService;
    private final PrepayService prepayService;
    private final RefundService refundService;
    private final PaymentPlanMapper planMapper;

    public BillingController(
            BillService billService,
            PaymentService paymentService,
            AllocationService allocationService,
            PrepayService prepayService,
            RefundService refundService,
            PaymentPlanMapper planMapper) {
        this.billService = billService;
        this.paymentService = paymentService;
        this.allocationService = allocationService;
        this.prepayService = prepayService;
        this.refundService = refundService;
        this.planMapper = planMapper;
    }

    // ---- 缴费计划 ----
    @GetMapping("/billing/plans")
    public ApiResponse<List<PaymentPlan>> plans(@RequestParam(required = false) Long contractId) {
        return ApiResponse.ok(contractId == null ? List.of()
                : planMapper.selectList(new LambdaQueryWrapper<PaymentPlan>()
                        .eq(PaymentPlan::getContractId, contractId)
                        .orderByAsc(PaymentPlan::getPeriodNo)), TraceIdUtil.get());
    }

    // ---- 账单 ----
    @GetMapping("/billing/bills")
    public ApiResponse<PageResult<Bill>> bills(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long contractId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(billService.page(page, pageSize, contractId, status), TraceIdUtil.get());
    }

    @PostMapping("/billing/issue")
    @Audited(module = "billing", action = "issue_bills")
    public ApiResponse<Integer> issueBills() {
        return ApiResponse.ok(billService.issueBills(), TraceIdUtil.get());
    }

    // ---- 收款 ----
    @GetMapping("/payments")
    public ApiResponse<PageResult<Payment>> payments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String confirmStatus) {
        return ApiResponse.ok(paymentService.page(page, pageSize, channel, confirmStatus), TraceIdUtil.get());
    }

    /** PC 线下收款登记（含核销）。 */
    @PostMapping("/billing/bills/{billId}/collect")
    @Audited(module = "billing", action = "collect")
    public ApiResponse<Payment> collect(@PathVariable Long billId, @RequestBody Map<String, Object> body) {
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String method = (String) body.get("paymentMethod");
        Bill bill = billService.get(billId);
        Payment payment = paymentService.registerConfirmed(
                bill.getContractId(), bill.getTenantId(), amount, method, "pc");
        allocationService.allocate(payment, "fifo");
        return ApiResponse.ok(payment, TraceIdUtil.get());
    }

    /** 工作端现场收款登记（待确认）。 */
    @PostMapping("/payments/worker/register")
    @Audited(module = "billing", action = "worker_register")
    public ApiResponse<Payment> workerRegister(@RequestBody Map<String, Object> body) {
        Long contractId = body.get("contractId") == null ? null : Long.valueOf(body.get("contractId").toString());
        Long tenantId = body.get("tenantId") == null ? null : Long.valueOf(body.get("tenantId").toString());
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        Payment payment = paymentService.registerWorkerPayment(contractId, tenantId, amount, (String) body.get("method"));
        return ApiResponse.ok(payment, TraceIdUtil.get());
    }

    /** 财务到账确认（FR-MPW-010）。 */
    @PostMapping("/payments/{paymentId}/confirm")
    @Audited(module = "billing", action = "confirm_arrival")
    public ApiResponse<Payment> confirmArrival(@PathVariable Long paymentId) {
        Payment payment = paymentService.confirmArrival(paymentId, SecurityUtils.currentUserIdOrNull());
        allocationService.allocate(payment, "fifo");
        return ApiResponse.ok(payment, TraceIdUtil.get());
    }

    @GetMapping("/payments/{paymentId}/allocations")
    public ApiResponse<List<BillPayment>> allocations(@PathVariable Long paymentId) {
        return ApiResponse.ok(allocationService.listAllocations(paymentId), TraceIdUtil.get());
    }

    // ---- 预收 ----
    @GetMapping("/prepays")
    public ApiResponse<List<Prepay>> prepays(@RequestParam Long contractId) {
        return ApiResponse.ok(prepayService.listByContract(contractId), TraceIdUtil.get());
    }

    // ---- 退款 ----
    @GetMapping("/refunds")
    public ApiResponse<List<RefundOrder>> refunds(@RequestParam(required = false) Long paymentId) {
        return ApiResponse.ok(refundService.list(paymentId), TraceIdUtil.get());
    }

    @PostMapping("/refunds")
    @Audited(module = "refund", action = "apply")
    public ApiResponse<RefundOrder> applyRefund(@RequestBody Map<String, Object> body) {
        Long paymentId = Long.valueOf(body.get("paymentId").toString());
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        return ApiResponse.ok(refundService.apply(paymentId, amount,
                (String) body.get("reason"), (String) body.get("channel")), TraceIdUtil.get());
    }

    @PostMapping("/refunds/{refundId}/submit")
    @Audited(module = "refund", action = "submit")
    public ApiResponse<Void> submitRefund(@PathVariable Long refundId) {
        refundService.submit(refundId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/refunds/{refundId}/execute")
    @Audited(module = "refund", action = "execute")
    public ApiResponse<RefundOrder> executeRefund(@PathVariable Long refundId, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(refundService.execute(refundId, body.get("thirdPartyRefundNo")), TraceIdUtil.get());
    }
}
