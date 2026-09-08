package com.ams.modules.contract.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.contract.entity.ContractTemplate;
import com.ams.modules.contract.service.ContractDocumentService;
import com.ams.modules.contract.service.ContractSlotCatalog;
import com.ams.modules.contract.service.ContractTemplateService;
import com.ams.platform.security.Audited;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合同模板（数据插槽）与 Word 在线预览/导出。
 */
@RestController
@RequestMapping("/api/v1")
public class ContractTemplateController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ContractTemplateService templateService;
    private final ContractDocumentService documentService;

    public ContractTemplateController(
            ContractTemplateService templateService, ContractDocumentService documentService) {
        this.templateService = templateService;
        this.documentService = documentService;
    }

    @GetMapping("/contract-templates/slots")
    public ApiResponse<List<Map<String, String>>> slots() {
        return ApiResponse.ok(ContractSlotCatalog.all(), TraceIdUtil.get());
    }

    @GetMapping("/contract-templates")
    public ApiResponse<PageResult<ContractTemplate>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(templateService.page(page, pageSize, keyword, enabled), TraceIdUtil.get());
    }

    @GetMapping("/contract-templates/{id}")
    public ApiResponse<ContractTemplate> get(@PathVariable Long id) {
        return ApiResponse.ok(templateService.get(id), TraceIdUtil.get());
    }

    @PostMapping("/contract-templates")
    @Audited(module = "contract_template", action = "create")
    public ApiResponse<ContractTemplate> create(@RequestBody ContractTemplate body) {
        return ApiResponse.ok(templateService.create(body), TraceIdUtil.get());
    }

    @PutMapping("/contract-templates/{id}")
    @Audited(module = "contract_template", action = "update")
    public ApiResponse<ContractTemplate> update(@PathVariable Long id, @RequestBody ContractTemplate body) {
        return ApiResponse.ok(templateService.update(id, body), TraceIdUtil.get());
    }

    @PostMapping("/contract-templates/{id}/preview")
    public ApiResponse<Map<String, Object>> previewTemplate(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, String> overrides = stringMap(body == null ? null : body.get("slots"));
        boolean sample = body == null || body.get("sample") == null
                || Boolean.parseBoolean(body.get("sample").toString());
        return ApiResponse.ok(documentService.previewTemplate(id, overrides, sample), TraceIdUtil.get());
    }

    @PostMapping("/contract-templates/{id}/export")
    @Audited(module = "contract_template", action = "export")
    public ApiResponse<Map<String, Object>> exportTemplate(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, String> overrides = stringMap(body == null ? null : body.get("slots"));
        boolean sample = body == null || body.get("sample") == null
                || Boolean.parseBoolean(body.get("sample").toString());
        return ApiResponse.ok(documentService.exportTemplateDocx(id, overrides, sample), TraceIdUtil.get());
    }

    @GetMapping("/contracts/{contractId}/document/preview")
    public ApiResponse<Map<String, Object>> previewContract(@PathVariable Long contractId) {
        return ApiResponse.ok(documentService.previewContract(contractId), TraceIdUtil.get());
    }

    /** 生成前拉取模板插槽与默认值，供前端填写选项。 */
    @GetMapping("/contracts/{contractId}/document/prepare")
    public ApiResponse<Map<String, Object>> prepare(
            @PathVariable Long contractId, @RequestParam(required = false) Long templateId) {
        return ApiResponse.ok(documentService.prepareGenerate(contractId, templateId), TraceIdUtil.get());
    }

    /** 草稿预览（不落库）：按模板 + 已填插槽渲染。 */
    @PostMapping("/contracts/{contractId}/document/preview")
    public ApiResponse<Map<String, Object>> draftPreview(
            @PathVariable Long contractId, @RequestBody(required = false) Map<String, Object> body) {
        Long templateId = null;
        if (body != null && body.get("templateId") != null) {
            templateId = Long.valueOf(body.get("templateId").toString());
        }
        Map<String, String> overrides = stringMap(body == null ? null : body.get("slots"));
        return ApiResponse.ok(
                documentService.previewContract(contractId, templateId, overrides, true), TraceIdUtil.get());
    }

    @PostMapping("/contracts/{contractId}/document/generate")
    @Audited(module = "contract", action = "generate_doc")
    public ApiResponse<Map<String, Object>> generate(
            @PathVariable Long contractId, @RequestBody(required = false) Map<String, Object> body) {
        Long templateId = null;
        if (body != null && body.get("templateId") != null) {
            templateId = Long.valueOf(body.get("templateId").toString());
        }
        Map<String, String> overrides = stringMap(body == null ? null : body.get("slots"));
        return ApiResponse.ok(documentService.generate(contractId, templateId, overrides), TraceIdUtil.get());
    }

    @GetMapping("/contracts/{contractId}/document/export")
    @Audited(module = "contract", action = "export_doc")
    public ResponseEntity<ByteArrayResource> exportContract(@PathVariable Long contractId) {
        byte[] bytes = documentService.exportContractDocxBytes(contractId);
        String fileName = documentService.exportContractFileName(contractId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(DOCX)
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new HashMap<>();
        }
        Map<String, String> out = new HashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k.toString(), v.toString());
            }
        });
        return out;
    }
}
