package com.ams.modules.config.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.config.entity.ConfigVersion;
import com.ams.modules.config.service.ConfigVersionService;
import com.ams.modules.regulation.entity.RegulationReport;
import com.ams.modules.regulation.service.RegulationReportService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.MaskingService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参数版本 / 监管报送 / 脱敏矩阵接口（FR-CFG-002、FR-REG-001、FR-COM-005）。
 */
@RestController
@RequestMapping("/api/v1")
public class ConfigController {

    private final ConfigVersionService configVersionService;
    private final RegulationReportService regulationReportService;
    private final MaskingService maskingService;

    public ConfigController(
            ConfigVersionService configVersionService,
            RegulationReportService regulationReportService,
            MaskingService maskingService) {
        this.configVersionService = configVersionService;
        this.regulationReportService = regulationReportService;
        this.maskingService = maskingService;
    }

    // ---- 参数版本 ----
    @GetMapping("/config/versions")
    public ApiResponse<List<ConfigVersion>> history(@RequestParam(required = false) String configKey) {
        return ApiResponse.ok(configVersionService.history(configKey), TraceIdUtil.get());
    }

    @GetMapping("/config/versions/snapshot")
    public ApiResponse<Map<String, Object>> snapshot(@RequestParam(required = false) String asOfDate) {
        LocalDate date = asOfDate == null ? null : LocalDate.parse(asOfDate);
        return ApiResponse.ok(configVersionService.snapshot(date), TraceIdUtil.get());
    }

    @GetMapping("/config/versions/effective")
    public ApiResponse<ConfigVersion> effective(
            @RequestParam String configKey, @RequestParam(required = false) String asOfDate) {
        LocalDate date = asOfDate == null ? null : LocalDate.parse(asOfDate);
        return ApiResponse.ok(configVersionService.effectiveVersion(configKey, date), TraceIdUtil.get());
    }

    @PostMapping("/config/versions")
    @Audited(module = "config", action = "change")
    public ApiResponse<ConfigVersion> change(@RequestBody Map<String, Object> body) {
        LocalDate effectiveDate = body.get("effectiveDate") == null ? null
                : LocalDate.parse(body.get("effectiveDate").toString());
        return ApiResponse.ok(configVersionService.change(
                (String) body.get("configKey"), (String) body.get("configValue"), effectiveDate), TraceIdUtil.get());
    }

    @GetMapping("/config/masking-rule")
    public ApiResponse<Map<String, Object>> maskingRule() {
        return ApiResponse.ok(maskingService.getMatrix(), TraceIdUtil.get());
    }

    @PutMapping("/config/masking-rule")
    @Audited(module = "config", action = "update_masking")
    public ApiResponse<Map<String, Object>> updateMasking(@RequestBody Map<String, Object> body) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            return ApiResponse.ok(maskingService.updateMatrix(json), TraceIdUtil.get());
        } catch (Exception e) {
            return ApiResponse.ok(maskingService.getMatrix(), TraceIdUtil.get());
        }
    }

    // ---- 监管报送 ----
    @GetMapping("/regulation/reports")
    public ApiResponse<List<RegulationReport>> regulationReports(@RequestParam(required = false) String reportType) {
        return ApiResponse.ok(regulationReportService.list(reportType), TraceIdUtil.get());
    }

    @PostMapping("/regulation/reports")
    @Audited(module = "regulation", action = "build")
    public ApiResponse<RegulationReport> buildReport(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(regulationReportService.build(
                (String) body.get("reportType"), (String) body.get("period"), (String) body.get("contentJson")),
                TraceIdUtil.get());
    }

    @PostMapping("/regulation/reports/generate")
    @Audited(module = "regulation", action = "generate")
    public ApiResponse<RegulationReport> generateReport(@RequestBody Map<String, Object> body) {
        Long companyId = body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString());
        return ApiResponse.ok(regulationReportService.generate(
                (String) body.get("reportType"), (String) body.get("period"), companyId), TraceIdUtil.get());
    }

    @GetMapping("/regulation/reports/{id}")
    public ApiResponse<RegulationReport> regulationReport(@PathVariable Long id) {
        return ApiResponse.ok(regulationReportService.get(id), TraceIdUtil.get());
    }

    @PostMapping("/regulation/reports/{id}/review")
    @Audited(module = "regulation", action = "review")
    public ApiResponse<RegulationReport> review(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(regulationReportService.review(id, body.get("contentJson")), TraceIdUtil.get());
    }

    @PostMapping("/regulation/reports/{id}/submit")
    @Audited(module = "regulation", action = "submit")
    public ApiResponse<RegulationReport> submit(@PathVariable Long id) {
        return ApiResponse.ok(regulationReportService.submit(id), TraceIdUtil.get());
    }
}
