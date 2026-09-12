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
import com.ams.modules.billing.service.WechatPayService;
import com.ams.modules.pricing.entity.PaymentPlan;
import com.ams.modules.pricing.mapper.PaymentPlanMapper;
import com.ams.platform.security.Audited;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.ams.platform.security.RequiresPerm;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
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
    private final WechatPayService wechatPayService;
    private final OwnershipResolver ownershipResolver;
    private final RbacService rbacService;

    public BillingController(
            BillService billService,
            PaymentService paymentService,
            AllocationService allocationService,
            PrepayService prepayService,
            RefundService refundService,
            PaymentPlanMapper planMapper,
            WechatPayService wechatPayService,
            OwnershipResolver ownershipResolver,
            RbacService rbacService) {
        this.billService = billService;
        this.paymentService = paymentService;
        this.allocationService = allocationService;
        this.prepayService = prepayService;
        this.refundService = refundService;
        this.planMapper = planMapper;
        this.wechatPayService = wechatPayService;
        this.ownershipResolver = ownershipResolver;
        this.rbacService = rbacService;
    }

    // ---- 缴费计划 ----
    @GetMapping("/billing/plans")
    @RequiresPerm("billing.bill:view")
    public ApiResponse<List<PaymentPlan>> plans(@RequestParam(required = false) Long contractId) {
        if (contractId != null) {
            rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofContract(contractId));
        }
        return ApiResponse.ok(contractId == null ? List.of()
                : planMapper.selectList(new LambdaQueryWrapper<PaymentPlan>()
                        .eq(PaymentPlan::getContractId, contractId)
                        .orderByAsc(PaymentPlan::getPeriodNo)), TraceIdUtil.get());
    }

    // ---- 账单 ----
    @GetMapping("/billing/bills")
    @RequiresPerm("billing.bill:view")
    public ApiResponse<PageResult<Bill>> bills(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long contractId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(billService.page(page, pageSize, contractId, status), TraceIdUtil.get());
    }

    @GetMapping("/billing/bills/{billId}")
    @RequiresPerm("billing.bill:view")
    public ApiResponse<Bill> bill(@PathVariable Long billId) {
        assertBill(billId);
        return ApiResponse.ok(billService.get(billId), TraceIdUtil.get());
    }

    @PostMapping("/billing/issue")
    @RequiresPerm("billing.bill:create")
    @Audited(module = "billing", action = "issue_bills")
    public ApiResponse<Integer> issueBills() {
        return ApiResponse.ok(billService.issueBills(), TraceIdUtil.get());
    }

    // ---- 收款 ----
    @GetMapping("/payments")
    @RequiresPerm("finance.payment:view")
    public ApiResponse<PageResult<Payment>> payments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String confirmStatus) {
        return ApiResponse.ok(paymentService.page(page, pageSize, channel, confirmStatus), TraceIdUtil.get());
    }

    /** PC 线下收款登记（含核销）。strategy: fifo|specified|proportional */
    @PostMapping("/billing/bills/{billId}/collect")
    @RequiresPerm("finance.payment:create")
    @Audited(module = "billing", action = "collect")
    public ApiResponse<Payment> collect(@PathVariable Long billId, @RequestBody Map<String, Object> body) {
        assertBill(billId);
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String method = (String) body.get("paymentMethod");
        String strategy = body.get("strategy") == null ? "specified" : body.get("strategy").toString();
        Bill bill = billService.get(billId);
        Payment payment = paymentService.registerConfirmed(
                bill.getContractId(), bill.getTenantId(), amount, method, "pc");
        allocationService.allocate(payment, strategy, List.of(billId), null);
        return ApiResponse.ok(payment, TraceIdUtil.get());
    }

    /** 用户端微信支付（FR-MPU-004）。 */
    @PostMapping("/billing/payments/wechat")
    @RequiresPerm("finance.payment:create")
    @Audited(module = "billing", action = "wechat_pay")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> wechatPay(@RequestBody Map<String, Object> body) {
        List<Long> billIds = ((List<?>) body.get("billIds")).stream()
                .map(v -> Long.valueOf(v.toString()))
                .toList();
        String strategy = body.get("strategy") == null ? "specified" : body.get("strategy").toString();
        // 一笔支付可能覆盖多张账单，必须逐张确认都在范围内
        billIds.forEach(this::assertBill);
        return ApiResponse.ok(wechatPayService.createJsapiPayment(billIds, strategy), TraceIdUtil.get());
    }

    /** 工作端现场收款登记（待确认）。 */
    @PostMapping("/payments/worker/register")
    @RequiresPerm("finance.payment:create")
    @Audited(module = "billing", action = "worker_register")
    public ApiResponse<Payment> workerRegister(@RequestBody Map<String, Object> body) {
        Long contractId = body.get("contractId") == null ? null : Long.valueOf(body.get("contractId").toString());
        Long tenantId = body.get("tenantId") == null ? null : Long.valueOf(body.get("tenantId").toString());
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        // 现场登记会立即产生收款记录，归属同样要校验
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofContract(contractId));
        Payment payment = paymentService.registerWorkerPayment(contractId, tenantId, amount, (String) body.get("method"));
        return ApiResponse.ok(payment, TraceIdUtil.get());
    }

    /** 财务到账确认（FR-MPW-010）。 */
    @PostMapping("/payments/{paymentId}/confirm")
    @RequiresPerm("finance.payment:update")
    @Audited(module = "billing", action = "confirm_arrival")
    public ApiResponse<Payment> confirmArrival(@PathVariable Long paymentId, @RequestBody(required = false) Map<String, Object> body) {
        assertPayment(paymentId);
        Payment payment = paymentService.confirmArrival(paymentId, SecurityUtils.currentUserIdOrNull());
        String strategy = body == null || body.get("strategy") == null ? "fifo" : body.get("strategy").toString();
        List<Long> billIds = null;
        if (body != null && body.get("billIds") instanceof List<?> list) {
            billIds = list.stream().map(v -> Long.valueOf(v.toString())).toList();
        }
        allocationService.allocate(payment, strategy, billIds, null);
        return ApiResponse.ok(payment, TraceIdUtil.get());
    }

    @PostMapping("/payments/{paymentId}/reallocate")
    @RequiresPerm("finance.payment:update")
    @Audited(module = "billing", action = "reallocate")
    public ApiResponse<BigDecimal> reallocate(@PathVariable Long paymentId, @RequestBody Map<String, Object> body) {
        assertPayment(paymentId);
        String strategy = body.get("strategy") == null ? "fifo" : body.get("strategy").toString();
        List<Long> billIds = null;
        if (body.get("billIds") instanceof List<?> list) {
            billIds = list.stream().map(v -> Long.valueOf(v.toString())).toList();
        }
        return ApiResponse.ok(allocationService.reallocate(paymentId, strategy, billIds), TraceIdUtil.get());
    }

    @GetMapping("/payments/{paymentId}/allocations")
    @RequiresPerm("finance.payment:view")
    public ApiResponse<List<BillPayment>> allocations(@PathVariable Long paymentId) {
        assertPayment(paymentId);
        return ApiResponse.ok(allocationService.listAllocations(paymentId), TraceIdUtil.get());
    }

    // ---- 预收 ----
    @GetMapping("/prepays")
    @RequiresPerm("finance.payment:view")
    public ApiResponse<List<Prepay>> prepays(@RequestParam Long contractId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofContract(contractId));
        return ApiResponse.ok(prepayService.listByContract(contractId), TraceIdUtil.get());
    }

    @GetMapping("/prepays/statement")
    @RequiresPerm("finance.payment:view")
    public ApiResponse<Map<String, Object>> prepayStatement(@RequestParam Long contractId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofContract(contractId));
        return ApiResponse.ok(prepayService.statement(contractId), TraceIdUtil.get());
    }

    // ---- 退款 ----
    @GetMapping("/refunds")
    @RequiresPerm("finance.refund:view")
    public ApiResponse<List<RefundOrder>> refunds(@RequestParam(required = false) Long paymentId) {
        if (paymentId != null) {
            assertPayment(paymentId);
        }
        return ApiResponse.ok(refundService.list(paymentId), TraceIdUtil.get());
    }

    @PostMapping("/refunds")
    @RequiresPerm("finance.refund:create")
    @Audited(module = "refund", action = "apply")
    public ApiResponse<RefundOrder> applyRefund(@RequestBody Map<String, Object> body) {
        Long paymentId = Long.valueOf(body.get("paymentId").toString());
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        assertPayment(paymentId);
        return ApiResponse.ok(refundService.apply(paymentId, amount,
                (String) body.get("reason"), (String) body.get("channel")), TraceIdUtil.get());
    }

    @PostMapping("/refunds/{refundId}/submit")
    @RequiresPerm("finance.refund:update")
    @Audited(module = "refund", action = "submit")
    public ApiResponse<Void> submitRefund(@PathVariable Long refundId) {
        assertRefund(refundId);
        refundService.submit(refundId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/refunds/{refundId}/execute")
    @RequiresPerm("finance.refund:update")
    @Audited(module = "refund", action = "execute")
    public ApiResponse<RefundOrder> executeRefund(@PathVariable Long refundId, @RequestBody Map<String, String> body) {
        assertRefund(refundId);
        return ApiResponse.ok(refundService.execute(refundId, body.get("thirdPartyRefundNo")), TraceIdUtil.get());
    }

    private void assertBill(Long billId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofBill(billId));
    }

    private void assertPayment(Long paymentId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofPayment(paymentId));
    }

    private void assertRefund(Long refundId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofRefundOrder(refundId));
    }
}
