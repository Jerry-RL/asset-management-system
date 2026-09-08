package com.ams.modules.invoice.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发票税率配置接口（FR-INV-001 发票税率子功能）。
 */
@RestController
@RequestMapping("/api/v1/invoice-tax-rates")
public class InvoiceTaxRateController {

    private final JdbcTemplate jdbcTemplate;

    public InvoiceTaxRateController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(jdbcTemplate.queryForList(
                "SELECT id, tax_code AS tax_code, tax_name AS tax_name, rate, effective_date, status FROM invoice_tax_rate ORDER BY id"),
                TraceIdUtil.get());
    }

    @PostMapping
    public ApiResponse<Void> save(@RequestBody Map<String, Object> body) {
        jdbcTemplate.update(
                "INSERT INTO invoice_tax_rate (tax_code, tax_name, rate, effective_date, status) VALUES (?, ?, ?, ?, ?)",
                body.get("taxCode"), body.get("taxName"), body.get("rate"),
                body.get("effectiveDate") == null ? java.time.LocalDate.now()
                        : java.time.LocalDate.parse(body.get("effectiveDate").toString()),
                body.get("status") == null ? 1 : Integer.parseInt(body.get("status").toString()));
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
}
