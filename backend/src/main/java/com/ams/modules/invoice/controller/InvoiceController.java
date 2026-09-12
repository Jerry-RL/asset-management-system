package com.ams.modules.invoice.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.invoice.entity.Invoice;
import com.ams.modules.invoice.entity.InvoiceTitle;
import com.ams.modules.invoice.service.InvoiceService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.RequiresPerm;
import com.ams.platform.security.SecurityUtils;
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
 * 发票接口（FR-INV-*）。
 *
 * <h2>数据范围（设计 6.1）</h2>
 * 发票表本身没有公司列，归属沿 {@code invoice → payment/bill → contract → asset} 推导；
 * 推导不到时交 {@code assertCompanyAccess} 判定（受限账号拒绝）。开票与红冲都会产生
 * 法律责任，因此写路径必须逐个断言，不能只依赖列表过滤。
 */
@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final OwnershipResolver ownershipResolver;
    private final RbacService rbacService;

    public InvoiceController(
            InvoiceService invoiceService,
            OwnershipResolver ownershipResolver,
            RbacService rbacService) {
        this.invoiceService = invoiceService;
        this.ownershipResolver = ownershipResolver;
        this.rbacService = rbacService;
    }

    @GetMapping
    @RequiresPerm("finance.invoice:view")
    public ApiResponse<List<Invoice>> list(@RequestParam(required = false) Long paymentId) {
        return ApiResponse.ok(invoiceService.list(paymentId), TraceIdUtil.get());
    }

    @PostMapping
    @RequiresPerm("finance.invoice:create")
    @Audited(module = "invoice", action = "issue")
    public ApiResponse<Invoice> issue(@RequestBody Map<String, Object> body) {
        Long paymentId = Long.valueOf(body.get("paymentId").toString());
        Long titleId = Long.valueOf(body.get("titleId").toString());
        BigDecimal taxRate = body.get("taxRate") == null ? null : new BigDecimal(body.get("taxRate").toString());
        // 新建没有已存记录：请求体给出的收款单是唯一的归属来源
        assertPayment(paymentId);
        return ApiResponse.ok(invoiceService.issue(paymentId, titleId, taxRate), TraceIdUtil.get());
    }

    @PostMapping("/{invoiceId}/red-flush")
    @RequiresPerm("finance.invoice:update")
    @Audited(module = "invoice", action = "red_flush")
    public ApiResponse<Invoice> redFlush(@PathVariable Long invoiceId) {
        assertInvoice(invoiceId);
        return ApiResponse.ok(invoiceService.redFlush(invoiceId), TraceIdUtil.get());
    }

    @GetMapping("/titles")
    @RequiresPerm("finance.invoice:view")
    public ApiResponse<List<InvoiceTitle>> titles(@RequestParam Long tenantId) {
        return ApiResponse.ok(invoiceService.listTitles(tenantId), TraceIdUtil.get());
    }

    @PostMapping("/titles")
    @RequiresPerm("finance.invoice:create")
    @Audited(module = "invoice", action = "create_title")
    public ApiResponse<InvoiceTitle> createTitle(@RequestBody InvoiceTitle title) {
        return ApiResponse.ok(invoiceService.createTitle(title), TraceIdUtil.get());
    }

    private void assertInvoice(Long invoiceId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofInvoice(invoiceId));
    }

    private void assertPayment(Long paymentId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofPayment(paymentId));
    }
}
