package com.ams.modules.io.controller;

import com.ams.common.csv.CsvUtf8;
import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.io.service.ImportExportService;
import com.ams.platform.security.Audited;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/io")
public class ImportExportController {

    private final ImportExportService importExportService;

    public ImportExportController(ImportExportService importExportService) {
        this.importExportService = importExportService;
    }

    @GetMapping("/assets/export")
    public ResponseEntity<byte[]> exportAssets(@RequestParam(required = false) Long projectId) {
        // 带 UTF-8 BOM，避免 Windows Excel 打开中文乱码
        byte[] bytes = CsvUtf8.toDownloadBytes(importExportService.exportAssetsCsv(projectId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"assets.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    @GetMapping("/bills/export")
    public ResponseEntity<byte[]> exportBills(@RequestParam(required = false) Long contractId) {
        byte[] bytes = CsvUtf8.toDownloadBytes(importExportService.exportBillsCsv(contractId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bills.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    @PostMapping("/assets/import")
    @Audited(module = "io", action = "import_assets")
    public ApiResponse<Map<String, Object>> importAssets(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importExportService.importAssetsCsv(file), TraceIdUtil.get());
    }

    @PostMapping("/tenants/import")
    @Audited(module = "io", action = "import_tenants")
    public ApiResponse<Map<String, Object>> importTenants(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importExportService.importTenantsCsv(file), TraceIdUtil.get());
    }
}
