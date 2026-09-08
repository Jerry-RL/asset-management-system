package com.ams.platform.security;

import com.ams.modules.system.entity.RolePermission;
import org.springframework.stereotype.Service;

/**
 * 字段脱敏服务（FR-COM-005 / NFR-DSEC-008）。
 *
 * 按「角色 × 页面/接口 × 字段」矩阵决定返回明文/掩码/不返回。
 * 简化实现：基于敏感字段类型提供掩码；权限维度由调用方传入「是否有明文权限」。
 */
@Service
public class MaskingService {

    /** 手机号掩码 138****1234 */
    public String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 证件号掩码：保留前 3 后 4 */
    public String maskIdNo(String idNo) {
        if (idNo == null || idNo.length() < 8) {
            return idNo;
        }
        return idNo.substring(0, 3) + "********" + idNo.substring(idNo.length() - 4);
    }

    /** 银行卡掩码：保留后 4 位 */
    public String maskBankCard(String card) {
        if (card == null || card.length() < 4) {
            return card;
        }
        return "**** **** **** " + card.substring(card.length() - 4);
    }

    /** 通用掩码：保留首尾，中间打 * */
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

    /**
     * 字段级脱敏（简化矩阵）：无明文权限时按字段类型掩码，有权限返回原文。
     * 矩阵的完整「角色×页面×字段」配置存 config_version(config_key=masking_rule)。
     */
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

    // 保留引用，避免未使用告警；实际矩阵扩展点。
    public String applyRoleFieldMask(String fieldType, String value, RolePermission permission) {
        boolean plain = permission != null && "view_plain".equals(permission.getAction());
        return maskField(fieldType, value, plain);
    }
}
