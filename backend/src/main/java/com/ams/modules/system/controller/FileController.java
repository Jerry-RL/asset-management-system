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

    /**
     * 公开读取对象（{@code ObjectStorageClient#resolveUrl} 的落地端点）。
     *
     * <p>上传接口返回的 {@code url} 就是要内嵌到 {@code <img>} / 合同文档里的地址，
     * 而 {@code <img>} 无法携带 Authorization 头，故本端点放行匿名访问 ——
     * 安全性由「对象键含随机 UUID、不可枚举」保证（能力型 URL）。
     *
     * <p>响应为 inline，便于图片直接渲染；路径经 LocalObjectStorageClient 归一化校验，
     * 无法借此越出上传根目录。
     *
     * @param objectKey 对象键，含目录前缀，如 {@code 2026-09-10/<uuid>_a.png}
     */
    @GetMapping("/object/{*objectKey}")
    public ResponseEntity<InputStreamResource> object(@PathVariable String objectKey) {
        // 前导斜杠由 {*...} 捕获时带上，需剥离后才能与库中 object_key 对齐
        String key = objectKey != null && objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        FileMetadata meta = fileService.findByObjectKey(key);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (meta.getContentType() != null && !meta.getContentType().isBlank()) {
            mediaType = MediaType.parseMediaType(meta.getContentType());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(mediaType)
                .body(new InputStreamResource(fileService.openStream(meta)));
    }
}
