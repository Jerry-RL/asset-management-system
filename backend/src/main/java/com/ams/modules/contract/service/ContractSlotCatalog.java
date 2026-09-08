package com.ams.modules.contract.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合同模板数据插槽目录（FR-CON 模板生成）。
 */
public final class ContractSlotCatalog {

    private ContractSlotCatalog() {
    }

    public static List<Map<String, String>> all() {
        return List.of(
                slot("contractNo", "合同编号", "合同系统编号"),
                slot("lessorName", "出租方名称", "默认资产管理单位"),
                slot("tenantName", "承租方名称", "租户姓名/企业名"),
                slot("tenantPhone", "承租方电话", ""),
                slot("tenantIdNo", "承租方证件号", "身份证或统一社会信用代码"),
                slot("tenantLegalRep", "法定代表人", "企业租户"),
                slot("assetNo", "资产编号", ""),
                slot("assetName", "资产名称", ""),
                slot("assetAddress", "资产地址", "省市区+详细地址"),
                slot("assetArea", "资产面积", "平方米"),
                slot("leaseArea", "租赁面积", "平方米"),
                slot("startDate", "起租日", "yyyy-MM-dd"),
                slot("endDate", "到期日", "yyyy-MM-dd"),
                slot("rentType", "租金类型代码", "fixed_monthly 等"),
                slot("rentTypeLabel", "租金类型", "中文标签"),
                slot("paymentCycle", "缴费周期代码", "monthly 等"),
                slot("paymentCycleLabel", "缴费周期", "中文标签"),
                slot("rentAmount", "每期租金", "元"),
                slot("depositAmount", "保证金", "元"),
                slot("remark", "备注约定", ""),
                slot("signDate", "签署日期", "默认当天"),
                slot("year", "年", ""),
                slot("month", "月", ""),
                slot("day", "日", ""));
    }

    public static Map<String, String> sampleValues() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("contractNo", "CT-DEMO-0001");
        m.put("lessorName", "某某资产管理有限公司");
        m.put("tenantName", "张三");
        m.put("tenantPhone", "13800000000");
        m.put("tenantIdNo", "320800199001011234");
        m.put("tenantLegalRep", "");
        m.put("assetNo", "ZC-001");
        m.put("assetName", "示例商铺 A-101");
        m.put("assetAddress", "江苏省淮安市清江浦区示例路 1 号");
        m.put("assetArea", "120.00");
        m.put("leaseArea", "120.00");
        m.put("startDate", "2026-01-01");
        m.put("endDate", "2026-12-31");
        m.put("rentType", "fixed_monthly");
        m.put("rentTypeLabel", "固定月租");
        m.put("paymentCycle", "monthly");
        m.put("paymentCycleLabel", "按月");
        m.put("rentAmount", "10000.00");
        m.put("depositAmount", "30000.00");
        m.put("remark", "双方无其他特别约定。");
        m.put("signDate", "2026-01-01");
        m.put("year", "2026");
        m.put("month", "01");
        m.put("day", "01");
        return m;
    }

    private static Map<String, String> slot(String key, String label, String hint) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("hint", hint);
        m.put("placeholder", "{{" + key + "}}");
        return m;
    }
}
