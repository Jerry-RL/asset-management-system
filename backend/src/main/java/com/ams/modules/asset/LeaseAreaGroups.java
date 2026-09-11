package com.ams.modules.asset;

import com.ams.modules.contract.ContractStatus;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 合同占用面积的状态分组（面积预算口径的单一来源）。
 *
 * <p>只有这些状态的合同才占用计租单元面积。语义上刻意区分<b>预留</b>与<b>生效占用</b>：
 * 审批中的合同尚未生效，但必须预留面积，否则两个并发签约会同时通过校验而超租
 * （既有 {@code LeaseBundleService.createSplit} 只比对单次请求内面积，正是此缺陷）。
 *
 * <p>与 {@link AssetLeaseGroups} 同源同范式：常量集合即 SQL 字面量来源，
 * 避免「各处理解不一致导致统计对不上账」。
 */
public final class LeaseAreaGroups {

    private LeaseAreaGroups() {
    }

    /** 审批中：尚未生效，但需预留面积以防并发超租。 */
    public static final Set<String> RESERVING = Set.of(ContractStatus.APPROVING);

    /** 生效占用：已签约产生租金，占用单元面积。 */
    public static final Set<String> OCCUPYING = Set.of(
            ContractStatus.ACTIVE, ContractStatus.EXPIRING, ContractStatus.RENEWABLE);

    /** 面积持有 = 预留 + 生效占用。面积预算校验使用此集合。 */
    public static final Set<String> AREA_HOLDING = union(RESERVING, OCCUPYING);

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    /**
     * 生成可直接嵌入 SQL 的 IN 字面量，如 {@code ('active','expiring')}。
     *
     * <p>取值全部来自 {@link ContractStatus} 编译期常量（非用户输入），且额外做字符白名单校验，
     * 因此拼进聚合表达式不引入注入风险；这样字面量与常量集合同源，改分组规则不会漏改 SQL。
     */
    public static String sqlInList(Set<String> statuses) {
        return statuses.stream()
                .peek(LeaseAreaGroups::assertLiteralSafe)
                .sorted()
                .map(s -> "'" + s + "'")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "(" + s + ")")
                .orElse("(null)");
    }

    private static void assertLiteralSafe(String status) {
        if (status == null || !status.matches("[a-z_]+")) {
            throw new IllegalArgumentException("合同状态字面量非法: " + status);
        }
    }
}
