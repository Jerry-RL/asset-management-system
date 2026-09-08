package com.ams.modules.invoice.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.invoice.entity.Invoice;
import com.ams.modules.invoice.entity.InvoiceTitle;
import com.ams.modules.invoice.service.InvoiceService;
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
 * 发票接口（FR-INV-*）。
 */
@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public ApiResponse<List<Invoice>> list(@RequestParam(required = false) Long paymentId) {
        return ApiResponse.ok(invoiceService.list(paymentId), TraceIdUtil.get());
    }

    @PostMapping
    @Audited(module = "invoice", action = "issue")
    public ApiResponse<Invoice> issue(@RequestBody Map<String, Object> body) {
        Long paymentId = Long.valueOf(body.get("paymentId").toString());
        Long titleId = Long.valueOf(body.get("titleId").toString());
        BigDecimal taxRate = body.get("taxRate") == null ? null : new BigDecimal(body.get("taxRate").toString());
        return ApiResponse.ok(invoiceService.issue(paymentId, titleId, taxRate), TraceIdUtil.get());
    }

    @PostMapping("/{invoiceId}/red-flush")
    @Audited(module = "invoice", action = "red_flush")
    public ApiResponse<Invoice> redFlush(@PathVariable Long invoiceId) {
        return ApiResponse.ok(invoiceService.redFlush(invoiceId), TraceIdUtil.get());
    }

    @GetMapping("/titles")
    public ApiResponse<List<InvoiceTitle>> titles(@RequestParam Long tenantId) {
        return ApiResponse.ok(invoiceService.listTitles(tenantId), TraceIdUtil.get());
    }

    @PostMapping("/titles")
    @Audited(module = "invoice", action = "create_title")
    public ApiResponse<InvoiceTitle> createTitle(@RequestBody InvoiceTitle title) {
        return ApiResponse.ok(invoiceService.createTitle(title), TraceIdUtil.get());
    }
}
