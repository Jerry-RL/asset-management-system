package com.ams.modules.contract.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.contract.entity.ContractTemplate;
import com.ams.modules.contract.mapper.ContractTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合同模板管理：维护带数据插槽的 Word/HTML 模板。
 */
@Service
public class ContractTemplateService {

    private static final Pattern SLOT_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)\\s*\\}\\}");

    private final ContractTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    public ContractTemplateService(ContractTemplateMapper templateMapper, ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.objectMapper = objectMapper;
    }

    public PageResult<ContractTemplate> page(long page, long pageSize, String keyword, Boolean enabled) {
        Page<ContractTemplate> result = templateMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<ContractTemplate>()
                        .eq(enabled != null, ContractTemplate::getEnabled, enabled)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(ContractTemplate::getName, keyword)
                                .or()
                                .like(ContractTemplate::getTemplateCode, keyword))
                        .orderByDesc(ContractTemplate::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public ContractTemplate get(Long id) {
        ContractTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "合同模板不存在");
        }
        return t;
    }

    @Transactional
    public ContractTemplate create(ContractTemplate body) {
        if (body.getTemplateCode() == null || body.getTemplateCode().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "模板编码不能为空");
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "模板名称不能为空");
        }
        if (body.getContentHtml() == null || body.getContentHtml().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "模板正文不能为空");
        }
        Long exists = templateMapper.selectCount(new LambdaQueryWrapper<ContractTemplate>()
                .eq(ContractTemplate::getTemplateCode, body.getTemplateCode().trim()));
        if (exists != null && exists > 0) {
            throw new AppException(ErrorCode.CONFLICT, "模板编码已存在");
        }
        ContractTemplate t = new ContractTemplate();
        t.setTemplateCode(body.getTemplateCode().trim());
        t.setName(body.getName().trim());
        t.setContractType(blankTo(body.getContractType(), "lease"));
        t.setDescription(body.getDescription());
        t.setContentHtml(body.getContentHtml());
        t.setSlotsJson(resolveSlotsJson(body.getContentHtml(), body.getSlotsJson()));
        t.setVersion(body.getVersion() == null ? 1 : body.getVersion());
        t.setEnabled(body.getEnabled() == null || body.getEnabled());
        templateMapper.insert(t);
        return t;
    }

    @Transactional
    public ContractTemplate update(Long id, ContractTemplate body) {
        ContractTemplate t = get(id);
        if (body.getName() != null && !body.getName().isBlank()) {
            t.setName(body.getName().trim());
        }
        if (body.getContractType() != null && !body.getContractType().isBlank()) {
            t.setContractType(body.getContractType());
        }
        if (body.getDescription() != null) {
            t.setDescription(body.getDescription());
        }
        if (body.getContentHtml() != null && !body.getContentHtml().isBlank()) {
            t.setContentHtml(body.getContentHtml());
            t.setSlotsJson(resolveSlotsJson(body.getContentHtml(), body.getSlotsJson()));
            t.setVersion((t.getVersion() == null ? 1 : t.getVersion()) + 1);
        } else if (body.getSlotsJson() != null) {
            t.setSlotsJson(body.getSlotsJson());
        }
        if (body.getEnabled() != null) {
            t.setEnabled(body.getEnabled());
        }
        templateMapper.updateById(t);
        return t;
    }

    public List<String> extractSlots(String html) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (html == null) {
            return List.of();
        }
        Matcher m = SLOT_PATTERN.matcher(html);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return new ArrayList<>(keys);
    }

    public String fill(String html, Map<String, String> values) {
        if (html == null) {
            return "";
        }
        Matcher m = SLOT_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String val = values == null ? null : values.get(key);
            if (val == null) {
                val = "{{" + key + "}}";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(escapeHtml(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public List<String> parseSlotsJson(String slotsJson) {
        if (slotsJson == null || slotsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(slotsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return extractSlots(slotsJson);
        }
    }

    private String resolveSlotsJson(String html, String provided) {
        List<String> fromHtml = extractSlots(html);
        if (provided != null && !provided.isBlank()) {
            List<String> providedList = parseSlotsJson(provided);
            LinkedHashSet<String> merged = new LinkedHashSet<>(providedList);
            merged.addAll(fromHtml);
            return toJson(new ArrayList<>(merged));
        }
        return toJson(fromHtml);
    }

    private String toJson(List<String> slots) {
        try {
            return objectMapper.writeValueAsString(slots);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String blankTo(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
