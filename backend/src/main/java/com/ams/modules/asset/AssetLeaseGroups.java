package com.ams.modules.asset;

import java.util.Set;

/**
 * 租控状态分组（统计口径的单一来源）。
 *
 * <p>「闲置 / 盘活」是项目统计（stats 条 + 卡片视图）与资产利用率的口径基础，
 * 前端展示、SQL 聚合条件均由此处常量派生，避免各处理解不一致导致统计对不上账。
 *
 * <p>口径（与业务确认）：<br>
 * - 闲置 = 空置 + 招租中 —— 尚未产生收益、待盘活；<br>
 * - 盘活 = 在租 + 部分出租 —— 已签约产生收益；<br>
 * - 自用 / 占用 / 退租中 / 处置中 / 已退出 不计入二者，避免把「自用」误判为可盘活存量。
 */
public final class AssetLeaseGroups {

    private AssetLeaseGroups() {
    }

    /** 空置 */
    public static final String VACANT = "vacant";
    /** 招租中 */
    public static final String LEASING = "leasing";
    /** 在租 */
    public static final String LEASED = "leased";
    /** 部分出租 */
    public static final String PARTIAL_LEASED = "partial_leased";

    /** 闲置：尚未产生收益、待盘活 */
    public static final Set<String> IDLE = Set.of(VACANT, LEASING);

    /** 盘活：已签约产生收益 */
    public static final Set<String> REVITALIZED = Set.of(LEASED, PARTIAL_LEASED);

    /**
     * 生成可直接嵌入 SQL 的 IN 字面量，如 {@code ('leasing','vacant')}。
     *
     * <p>取值全部来自本类编译期常量（非用户输入），且额外做字符白名单校验，
     * 因此拼进 {@code QueryWrapper.select(...)} 的聚合表达式不引入注入风险；
     * 这样聚合列与常量集合同源，改动分组规则时不会漏改 SQL。
     */
    public static String sqlInList(Set<String> statuses) {
        return statuses.stream()
                .peek(AssetLeaseGroups::assertLiteralSafe)
                .sorted()
                .map(s -> "'" + s + "'")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "(" + s + ")")
                .orElse("(null)");
    }

    private static void assertLiteralSafe(String status) {
        if (status == null || !status.matches("[a-z_]+")) {
            throw new IllegalArgumentException("租控状态字面量非法: " + status);
        }
    }
}
