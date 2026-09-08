package com.ams.modules.system.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.system.entity.FileMetadata;
import com.ams.modules.system.service.FileService;
import com.ams.platform.security.Audited;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件上传下载（ADR-0007）。
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    @Audited(module = "file", action = "upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bizType", required = false) String bizType) {
        FileMetadata meta = fileService.upload(file, bizType);
        return ApiResponse.ok(fileService.toView(meta), TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> meta(@PathVariable Long id) {
        return ApiResponse.ok(fileService.toView(fileService.get(id)), TraceIdUtil.get());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
        FileMetadata meta = fileService.get(id);
        InputStream in = fileService.openStream(meta);
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (meta.getContentType() != null && !meta.getContentType().isBlank()) {
            mediaType = MediaType.parseMediaType(meta.getContentType());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + meta.getFileName() + "\"")
                .contentType(mediaType)
                .body(new InputStreamResource(in));
    }
}
