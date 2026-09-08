package com.ams.modules.meter.controller;

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
 * 水电公摊配置接口（FR-UTIL-006）。
 */
@RestController
@RequestMapping("/api/v1/apportion-configs")
public class ApportionConfigController {

    private final JdbcTemplate jdbcTemplate;

    public ApportionConfigController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(jdbcTemplate.queryForList(
                "SELECT id, company_id, project_id, apportion_basis, enabled FROM apportion_config ORDER BY id"),
                TraceIdUtil.get());
    }

    @PostMapping
    public ApiResponse<Void> save(@RequestBody Map<String, Object> body) {
        jdbcTemplate.update(
                "INSERT INTO apportion_config (company_id, project_id, apportion_basis, enabled) VALUES (?, ?, ?, ?)",
                body.get("companyId"), body.get("projectId"), body.get("apportionBasis"),
                body.get("enabled") == null ? true : Boolean.parseBoolean(body.get("enabled").toString()));
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
}
