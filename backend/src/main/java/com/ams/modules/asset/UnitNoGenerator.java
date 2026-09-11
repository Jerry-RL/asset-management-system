package com.ams.modules.asset;

/**
 * 计租单元编码生成（FR-MDM-003「统一编码规则 + 旧码映射」的落地）。
 *
 * <p>规则：{@code <资产编号>-U<序号>}，确定性、可读、可校验、可排序。
 *
 * <p>刻意<b>不使用随机后缀</b>：对比 {@code AssetStructureService.nextChildNo} 的
 * {@code UUID.substring(0,4)} 方案——随机编码无法校验、无法从编码反推归属、
 * 无法按编码排序，与「统一编码 + 旧码映射」的要求冲突。
 */
public final class UnitNoGenerator {

    private UnitNoGenerator() {
    }

    public static final String SEPARATOR = "-U";

    /** 顶层单元编号：{@code AST-2026-001-U1}。 */
    public static String of(String assetNo, int seq) {
        if (seq < 1) {
            throw new IllegalArgumentException("单元序号必须 >= 1: " + seq);
        }
        String base = (assetNo == null || assetNo.isBlank()) ? "AST" : assetNo.trim();
        return base + SEPARATOR + seq;
    }

    /**
     * 拆分产生的子单元编号：{@code AST-2026-001-U1-2}。
     * 因为 {@code unit_no} 在资产内唯一，追加层级天然不冲突，无需查重。
     */
    public static String childOf(String unitNo, int idx) {
        if (idx < 1) {
            throw new IllegalArgumentException("子单元序号必须 >= 1: " + idx);
        }
        String base = (unitNo == null || unitNo.isBlank()) ? "U" : unitNo.trim();
        return base + "-" + idx;
    }

    /** 编码是否属于该资产（用于导入/接口校验，拒绝跨资产错挂）。 */
    public static boolean belongsTo(String assetNo, String unitNo) {
        if (assetNo == null || unitNo == null) {
            return false;
        }
        return unitNo.startsWith(assetNo + SEPARATOR) || unitNo.startsWith(assetNo + "-");
    }
}
