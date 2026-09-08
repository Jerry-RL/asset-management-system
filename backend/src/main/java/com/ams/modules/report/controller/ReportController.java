package com.ams.modules.report.controller;

import com.ams.common.csv.CsvUtf8;
import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.report.service.ReportService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/asset-ledger")
    public ApiResponse<Map<String, Object>> assetLedger(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String leaseControlStatus) {
        return ApiResponse.ok(
                reportService.assetLedger(companyId, projectId, assetType, leaseControlStatus),
                TraceIdUtil.get());
    }

    @GetMapping("/lease-ledger")
    public ApiResponse<Map<String, Object>> leaseLedger(@RequestParam(required = false) String status) {
        return ApiResponse.ok(reportService.leaseLedger(status), TraceIdUtil.get());
    }

    @GetMapping("/collection")
    public ApiResponse<Map<String, Object>> collection(
            @RequestParam(required = false) Long contractId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String billType) {
        return ApiResponse.ok(reportService.collectionLedger(contractId, status, billType), TraceIdUtil.get());
    }

    @GetMapping("/maintenance")
    public ApiResponse<Map<String, Object>> maintenance(@RequestParam(required = false) String status) {
        return ApiResponse.ok(reportService.maintenanceLedger(status), TraceIdUtil.get());
    }

    @GetMapping("/{type}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable String type,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long contractId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String leaseControlStatus,
            @RequestParam(required = false) String billType) {
        Map<String, Object> report = switch (type) {
            case "asset-ledger" -> reportService.assetLedger(companyId, projectId, assetType, leaseControlStatus);
            case "lease-ledger" -> reportService.leaseLedger(status);
            case "collection" -> reportService.collectionLedger(contractId, status, billType);
            case "maintenance" -> reportService.maintenanceLedger(status);
            default -> throw new com.ams.common.exception.AppException(
                    com.ams.common.exception.ErrorCode.BAD_REQUEST, "未知报表类型");
        };
        // 带 UTF-8 BOM，避免 Windows Excel 打开中文乱码
        byte[] bytes = CsvUtf8.toDownloadBytes(reportService.toCsv(report));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + type + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }
}
