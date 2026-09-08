package com.ams.modules.contract.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.entity.ContractTemplate;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.modules.system.entity.FileMetadata;
import com.ams.modules.system.service.FileService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合同 Word 文档：按模板插槽填充、在线预览 HTML、导出 docx。
 */
@Service
public class ContractDocumentService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String DOCX_CT =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ContractMapper contractMapper;
    private final ContractTemplateService templateService;
    private final AssetMapper assetMapper;
    private final TenantMapper tenantMapper;
    private final FileService fileService;

    public ContractDocumentService(
            ContractMapper contractMapper,
            ContractTemplateService templateService,
            AssetMapper assetMapper,
            TenantMapper tenantMapper,
            FileService fileService) {
        this.contractMapper = contractMapper;
        this.templateService = templateService;
        this.assetMapper = assetMapper;
        this.tenantMapper = tenantMapper;
        this.fileService = fileService;
    }

    public Map<String, Object> previewTemplate(Long templateId, Map<String, String> overrides, boolean sample) {
        ContractTemplate template = templateService.get(templateId);
        Map<String, String> values = sample
                ? new LinkedHashMap<>(ContractSlotCatalog.sampleValues())
                : emptySlotMap(template);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (k != null && v != null) {
                    values.put(k, v);
                }
            });
        }
        String body = templateService.fill(template.getContentHtml(), values);
        String title = template.getName();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", template.getId());
        result.put("templateCode", template.getTemplateCode());
        result.put("title", title);
        result.put("slots", values);
        result.put("usedSlots", templateService.parseSlotsJson(template.getSlotsJson()));
        result.put("contentHtml", body);
        result.put("previewHtml", ContractWordExporter.wrapPreviewDocument(body, title));
        return result;
    }

    public Map<String, Object> previewContract(Long contractId) {
        return previewContract(contractId, null, null, false);
    }

    /**
     * 合同文档预览。draft=true 时按指定模板+插槽草稿预览，不落库。
     */
    public Map<String, Object> previewContract(
            Long contractId, Long templateId, Map<String, String> overrides, boolean draft) {
        Contract contract = requireContract(contractId);
        if (!draft && contract.getDocHtml() != null && !contract.getDocHtml().isBlank()
                && templateId == null && (overrides == null || overrides.isEmpty())) {
            String title = "合同 " + nullTo(contract.getContractNo(), String.valueOf(contractId));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("contractId", contractId);
            result.put("contractNo", contract.getContractNo());
            result.put("templateId", contract.getTemplateId());
            result.put("docFileId", contract.getDocFileId());
            result.put("contentHtml", contract.getDocHtml());
            result.put("previewHtml", ContractWordExporter.wrapPreviewDocument(contract.getDocHtml(), title));
            result.put("generated", true);
            return result;
        }
        Long tid = templateId != null ? templateId : contract.getTemplateId();
        if (tid == null) {
            tid = defaultTemplateId();
        }
        return generatePreviewOnly(contract, tid, overrides);
    }

    /** 生成前准备：返回模板所用插槽定义 + 合同默认填充值，供前端表单编辑。 */
    public Map<String, Object> prepareGenerate(Long contractId, Long templateId) {
        Contract contract = requireContract(contractId);
        Long tid = templateId != null ? templateId : contract.getTemplateId();
        if (tid == null) {
            tid = defaultTemplateId();
        }
        ContractTemplate template = templateService.get(tid);
        Map<String, String> values = buildSlotValues(contract);
        List<String> used = templateService.parseSlotsJson(template.getSlotsJson());
        if (used.isEmpty()) {
            used = templateService.extractSlots(template.getContentHtml());
        }
        Map<String, Map<String, String>> catalog = new LinkedHashMap<>();
        for (Map<String, String> s : ContractSlotCatalog.all()) {
            catalog.put(s.get("key"), s);
        }
        List<Map<String, String>> fields = new ArrayList<>();
        for (String key : used) {
            Map<String, String> meta = catalog.get(key);
            if (meta != null) {
                fields.add(meta);
            } else {
                Map<String, String> custom = new LinkedHashMap<>();
                custom.put("key", key);
                custom.put("label", key);
                custom.put("hint", "");
                custom.put("placeholder", "{{" + key + "}}");
                fields.add(custom);
            }
        }
        Map<String, Object> draft = generatePreviewOnly(contract, tid, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractId", contractId);
        result.put("contractNo", contract.getContractNo());
        result.put("templateId", template.getId());
        result.put("templateCode", template.getTemplateCode());
        result.put("templateName", template.getName());
        result.put("fields", fields);
        result.put("slots", values);
        result.put("previewHtml", draft.get("previewHtml"));
        result.put("contentHtml", draft.get("contentHtml"));
        return result;
    }

    @Transactional
    public Map<String, Object> generate(Long contractId, Long templateId, Map<String, String> overrides) {
        Contract contract = requireContract(contractId);
        Long tid = templateId != null ? templateId : contract.getTemplateId();
        if (tid == null) {
            tid = defaultTemplateId();
        }
        ContractTemplate template = templateService.get(tid);
        Map<String, String> values = buildSlotValues(contract);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (k != null && v != null) {
                    values.put(k, v);
                }
            });
        }
        String body = templateService.fill(template.getContentHtml(), values);
        String fileName = nullTo(contract.getContractNo(), "contract-" + contractId) + ".docx";
        byte[] docx = ContractWordExporter.toDocx(body, template.getName());
        FileMetadata file = fileService.storeBytes(docx, fileName, DOCX_CT, "contract");

        contract.setTemplateId(template.getId());
        contract.setDocHtml(body);
        contract.setDocFileId(file.getId());
        contractMapper.updateById(contract);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractId", contractId);
        result.put("contractNo", contract.getContractNo());
        result.put("templateId", template.getId());
        result.put("templateCode", template.getTemplateCode());
        result.put("docFileId", file.getId());
        result.put("fileName", fileName);
        result.put("slots", values);
        result.put("contentHtml", body);
        result.put("previewHtml", ContractWordExporter.wrapPreviewDocument(body, template.getName()));
        result.put("downloadPath", "/api/v1/files/" + file.getId() + "/download");
        return result;
    }

    public Map<String, Object> exportTemplateDocx(Long templateId, Map<String, String> overrides, boolean sample) {
        Map<String, Object> preview = previewTemplate(templateId, overrides, sample);
        String body = String.valueOf(preview.get("contentHtml"));
        String title = String.valueOf(preview.get("title"));
        String code = String.valueOf(preview.get("templateCode"));
        byte[] docx = ContractWordExporter.toDocx(body, title);
        String fileName = code + ".docx";
        FileMetadata file = fileService.storeBytes(docx, fileName, DOCX_CT, "contract_template");
        Map<String, Object> result = new LinkedHashMap<>(preview);
        result.put("docFileId", file.getId());
        result.put("fileName", fileName);
        result.put("downloadPath", "/api/v1/files/" + file.getId() + "/download");
        result.put("size", file.getSize());
        return result;
    }

    public byte[] exportContractDocxBytes(Long contractId) {
        Contract contract = requireContract(contractId);
        if (contract.getDocFileId() != null) {
            FileMetadata meta = fileService.get(contract.getDocFileId());
            try (var in = fileService.openStream(meta)) {
                return in.readAllBytes();
            } catch (Exception e) {
                throw new AppException(ErrorCode.INTERNAL_ERROR, "读取合同 Word 失败");
            }
        }
        Map<String, Object> generated = generate(contractId, contract.getTemplateId(), null);
        Long fileId = ((Number) generated.get("docFileId")).longValue();
        FileMetadata meta = fileService.get(fileId);
        try (var in = fileService.openStream(meta)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "导出合同 Word 失败");
        }
    }

    public String exportContractFileName(Long contractId) {
        Contract c = requireContract(contractId);
        return nullTo(c.getContractNo(), "contract-" + contractId) + ".docx";
    }

    public Map<String, String> buildSlotValues(Contract contract) {
        Map<String, String> values = emptySlotMap(null);
        values.put("contractNo", nullTo(contract.getContractNo(), ""));
        values.put("leaseArea", decimal(contract.getLeaseArea()));
        values.put("startDate", date(contract.getStartDate()));
        values.put("endDate", date(contract.getEndDate()));
        values.put("rentType", nullTo(contract.getRentType(), ""));
        values.put("rentTypeLabel", rentTypeLabel(contract.getRentType()));
        values.put("paymentCycle", nullTo(contract.getPaymentCycle(), ""));
        values.put("paymentCycleLabel", paymentCycleLabel(contract.getPaymentCycle()));
        values.put("rentAmount", decimal(contract.getRentAmount()));
        values.put("depositAmount", decimal(contract.getDepositAmount()));
        values.put("remark", nullTo(contract.getRemark(), "双方无其他特别约定。"));

        LocalDate today = LocalDate.now();
        values.put("signDate", DATE.format(today));
        values.put("year", String.valueOf(today.getYear()));
        values.put("month", String.format("%02d", today.getMonthValue()));
        values.put("day", String.format("%02d", today.getDayOfMonth()));
        values.put("lessorName", "资产管理单位");

        if (contract.getTenantId() != null) {
            Tenant tenant = tenantMapper.selectById(contract.getTenantId());
            if (tenant != null) {
                values.put("tenantName", nullTo(tenant.getName(), ""));
                values.put("tenantPhone", nullTo(tenant.getPhone(), ""));
                values.put("tenantIdNo", nullTo(tenant.getIdNo(), ""));
                values.put("tenantLegalRep", nullTo(tenant.getLegalRep(), ""));
            }
        }
        if (contract.getAssetId() != null) {
            Asset asset = assetMapper.selectById(contract.getAssetId());
            if (asset != null) {
                values.put("assetNo", nullTo(asset.getAssetNo(), ""));
                values.put("assetName", nullTo(asset.getName(), ""));
                values.put("assetArea", decimal(asset.getArea()));
                values.put("assetAddress", joinAddress(asset));
                if (contract.getLeaseArea() == null) {
                    values.put("leaseArea", decimal(asset.getArea()));
                }
            }
        }
        return values;
    }

    private Map<String, Object> generatePreviewOnly(Contract contract, Long templateId, Map<String, String> overrides) {
        ContractTemplate template = templateService.get(templateId);
        Map<String, String> values = buildSlotValues(contract);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (k != null && v != null) {
                    values.put(k, v);
                }
            });
        }
        String body = templateService.fill(template.getContentHtml(), values);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractId", contract.getId());
        result.put("contractNo", contract.getContractNo());
        result.put("templateId", template.getId());
        result.put("docFileId", contract.getDocFileId());
        result.put("slots", values);
        result.put("contentHtml", body);
        result.put("previewHtml", ContractWordExporter.wrapPreviewDocument(body, template.getName()));
        result.put("generated", false);
        return result;
    }

    private Long defaultTemplateId() {
        var page = templateService.page(1, 1, null, true);
        if (page.getList() == null || page.getList().isEmpty()) {
            throw new AppException(ErrorCode.NOT_FOUND, "暂无可用合同模板，请先配置模板");
        }
        return page.getList().get(0).getId();
    }

    private Map<String, String> emptySlotMap(ContractTemplate template) {
        Map<String, String> values = new HashMap<>();
        List<String> keys = template == null
                ? ContractSlotCatalog.all().stream().map(s -> s.get("key")).toList()
                : templateService.parseSlotsJson(template.getSlotsJson());
        if (keys.isEmpty()) {
            keys = ContractSlotCatalog.all().stream().map(s -> s.get("key")).toList();
        }
        for (String key : keys) {
            values.put(key, "");
        }
        return values;
    }

    private Contract requireContract(Long id) {
        Contract c = contractMapper.selectById(id);
        if (c == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "合同不存在");
        }
        return c;
    }

    private static String joinAddress(Asset asset) {
        StringBuilder sb = new StringBuilder();
        if (asset.getProvince() != null) {
            sb.append(asset.getProvince());
        }
        if (asset.getCity() != null) {
            sb.append(asset.getCity());
        }
        if (asset.getDistrict() != null) {
            sb.append(asset.getDistrict());
        }
        if (asset.getAddress() != null) {
            sb.append(asset.getAddress());
        }
        return sb.toString();
    }

    private static String rentTypeLabel(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "fixed_monthly" -> "固定月租";
            case "fixed_yearly" -> "固定年租";
            case "per_area" -> "按面积";
            case "per_unit" -> "按单元";
            case "negotiable" -> "面议";
            default -> code;
        };
    }

    private static String paymentCycleLabel(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "monthly" -> "按月";
            case "quarterly" -> "按季";
            case "yearly" -> "按年";
            default -> code;
        };
    }

    private static String decimal(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    private static String nullTo(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }
}
