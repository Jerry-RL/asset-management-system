package com.ams.platform.security;

import com.ams.modules.config.service.ConfigVersionService;
import com.ams.modules.system.entity.RolePermission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 字段脱敏服务（FR-COM-005 / NFR-DSEC-008）。
 * 矩阵存 config_version(config_key=masking_rule)：default/roles/fields → plain|mask|omit。
 */
@Service
public class MaskingService {

    private final ConfigVersionService configVersionService;
    private final ObjectMapper objectMapper;

    public MaskingService(ConfigVersionService configVersionService, ObjectMapper objectMapper) {
        this.configVersionService = configVersionService;
        this.objectMapper = objectMapper;
    }

    public String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public String maskIdNo(String idNo) {
        if (idNo == null || idNo.length() < 8) {
            return idNo;
        }
        return idNo.substring(0, 3) + "********" + idNo.substring(idNo.length() - 4);
    }

    public String maskBankCard(String card) {
        if (card == null || card.length() < 4) {
            return card;
        }
        return "**** **** **** " + card.substring(card.length() - 4);
    }

    public String mask(String value, int keepHead, int keepTail) {
        if (value == null || value.length() <= keepHead + keepTail) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(value, 0, keepHead);
        sb.append("*".repeat(Math.max(1, value.length() - keepHead - keepTail)));
        sb.append(value.substring(value.length() - keepTail));
        return sb.toString();
    }

    public String maskField(String fieldType, String value, boolean hasPlainPermission) {
        if (value == null) {
            return null;
        }
        if (hasPlainPermission) {
            return value;
        }
        return switch (fieldType) {
            case "phone" -> maskPhone(value);
            case "id_no" -> maskIdNo(value);
            case "bank_card" -> maskBankCard(value);
            default -> value;
        };
    }

    /**
     * 按矩阵解析策略：plain / mask / omit。
     * 当前用户角色列表命中 roles 配置则用角色策略，否则用 default，再与 fields 字段覆盖合并（字段优先）。
     */
    public String resolvePolicy(String fieldType, Set<String> roles) {
        Map<String, Object> rule = loadRule();
        String policy = stringVal(rule.get("default"), "mask");
        Object rolesObj = rule.get("roles");
        if (rolesObj instanceof Map<?, ?> roleMap && roles != null) {
            for (String role : roles) {
                Object p = roleMap.get(role);
                if (p != null) {
                    policy = p.toString();
                    break;
                }
            }
        }
        Object fieldsObj = rule.get("fields");
        if (fieldsObj instanceof Map<?, ?> fieldMap && fieldType != null && fieldMap.containsKey(fieldType)) {
            policy = fieldMap.get(fieldType).toString();
        }
        return policy;
    }

    public String apply(String fieldType, String value, Set<String> roles) {
        if (value == null) {
            return null;
        }
        String policy = resolvePolicy(fieldType, roles);
        return switch (policy) {
            case "plain" -> value;
            case "omit" -> null;
            default -> maskField(fieldType, value, false);
        };
    }

    public Map<String, Object> getMatrix() {
        return loadRule();
    }

    public Map<String, Object> updateMatrix(String json) {
        configVersionService.change("masking_rule", json, java.time.LocalDate.now());
        return loadRule();
    }

    public String applyRoleFieldMask(String fieldType, String value, RolePermission permission) {
        boolean plain = permission != null && "view_plain".equals(permission.getAction());
        return maskField(fieldType, value, plain);
    }

    private Map<String, Object> loadRule() {
        String raw = configVersionService.getValue("masking_rule",
                "{\"default\":\"mask\",\"fields\":{\"phone\":\"mask\",\"id_no\":\"mask\"}}");
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("default", "mask");
        }
    }

    private static String stringVal(Object v, String def) {
        return v == null ? def : v.toString();
    }
}
