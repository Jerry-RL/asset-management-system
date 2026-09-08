package com.ams.modules.meter.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.meter.service.ApportionService;
import com.ams.platform.security.Audited;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 水电公摊配置与分摊计算（FR-UTIL-006）。
 */
@RestController
@RequestMapping("/api/v1/apportion-configs")
public class ApportionConfigController {

    private final JdbcTemplate jdbcTemplate;
    private final ApportionService apportionService;

    public ApportionConfigController(JdbcTemplate jdbcTemplate, ApportionService apportionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.apportionService = apportionService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(jdbcTemplate.queryForList(
                "SELECT id, company_id AS \"companyId\", project_id AS \"projectId\","
                        + " apportion_basis AS \"apportionBasis\", enabled,"
                        + " unit_price_water AS \"unitPriceWater\","
                        + " unit_price_electric AS \"unitPriceElectric\","
                        + " unit_price_gas AS \"unitPriceGas\","
                        + " minimum_usage AS \"minimumUsage\""
                        + " FROM apportion_config ORDER BY id"),
                TraceIdUtil.get());
    }

    @PostMapping
    public ApiResponse<Void> save(@RequestBody Map<String, Object> body) {
        jdbcTemplate.update(
                "INSERT INTO apportion_config (company_id, project_id, apportion_basis, enabled,"
                        + " unit_price_water, unit_price_electric, unit_price_gas, minimum_usage)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                body.get("companyId"),
                body.get("projectId"),
                body.get("apportionBasis"),
                body.get("enabled") == null || Boolean.parseBoolean(body.get("enabled").toString()),
                toBd(body.get("unitPriceWater"), new BigDecimal("3.5")),
                toBd(body.get("unitPriceElectric"), new BigDecimal("0.8")),
                toBd(body.get("unitPriceGas"), new BigDecimal("2.5")),
                toBd(body.get("minimumUsage"), BigDecimal.ZERO));
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/compute")
    @Audited(module = "utility", action = "apportion")
    public ApiResponse<Map<String, Object>> compute(@RequestBody Map<String, Object> body) {
        Long projectId = Long.valueOf(body.get("projectId").toString());
        BigDecimal totalCost = new BigDecimal(body.get("totalCost").toString());
        String basis = body.get("basis") == null ? null : body.get("basis").toString();
        return ApiResponse.ok(apportionService.computeApportion(projectId, totalCost, basis), TraceIdUtil.get());
    }

    private static BigDecimal toBd(Object v, BigDecimal def) {
        if (v == null || v.toString().isBlank()) {
            return def;
        }
        return new BigDecimal(v.toString());
    }
}
