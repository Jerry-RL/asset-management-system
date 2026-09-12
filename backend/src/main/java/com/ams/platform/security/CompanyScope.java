package com.ams.platform.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户可访问的公司范围（设计 5.2）。
 *
 * <p>存在的唯一理由：把「不受限」与「无可见公司」这两种语义<strong>在类型上分开</strong>。
 * 历史上二者都用「空集合」表示，于是任何一次减法（角色排除清单）算空之后，
 * 都会从「排除干净」被误读成「放开全量」—— 这是本设计要根除的越权来源。
 *
 * <h2>两种状态</h2>
 * <ul>
 *   <li><strong>不受限</strong>（{@link #unrestricted()}）：不加 company 条件。只由
 *       「未切换公司 且 super_admin / dataScope=all 且无排除」产生。</li>
 *   <li><strong>受限</strong>（{@link #of(Collection)}）：携带具体公司 id 集合。
 *       集合算空时自动降级为<strong>拒绝哨兵</strong>（{@code {-1}}），
 *       {@link #isDenied()} 为真，{@link #allows(Long)} 对任何真实公司都返回 false。</li>
 * </ul>
 */
public final class CompanyScope {

    /** 拒绝哨兵：公司 id 恒为正数，因此 -1 匹配不到任何记录。 */
    private static final long DENY_SENTINEL = -1L;

    private final boolean unrestricted;
    private final Set<Long> ids;

    private CompanyScope(boolean unrestricted, Set<Long> ids) {
        this.unrestricted = unrestricted;
        this.ids = ids;
    }

    /** 不受限：调用方不加 company 条件。 */
    public static CompanyScope unrestricted() {
        return new CompanyScope(true, Set.of());
    }

    /**
     * 受限范围。
     *
     * @param companyIds 可访问公司；为 null 或空集合时降级为拒绝哨兵（绝不表示「不受限」）
     */
    public static CompanyScope of(Collection<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            return new CompanyScope(false, Set.of(DENY_SENTINEL));
        }
        return new CompanyScope(false, Set.copyOf(companyIds));
    }

    /**
     * 从基线中扣除排除集合（调用方需已把被排除公司展开为整棵子树）。
     *
     * <p><strong>只对受限范围有意义</strong>：不受限范围没有基线，调用方必须先
     * {@code CompanyScope.of(allCompanyIds())} 物化出全部公司再扣除。
     * 误对不受限范围调用只会把结果收窄成拒绝态（收敛方向是安全侧，不会放大权限）。
     */
    public CompanyScope minus(Collection<Long> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return this;
        }
        Set<Long> remaining = new LinkedHashSet<>(ids);
        remaining.removeAll(excluded);
        return of(remaining);
    }

    public boolean isUnrestricted() {
        return unrestricted;
    }

    /** 是否为「无任何可见公司」的拒绝态。 */
    public boolean isDenied() {
        return !unrestricted && ids.size() == 1 && ids.contains(DENY_SENTINEL);
    }

    /** 是否需要给查询加 company 过滤条件。 */
    public boolean hasFilter() {
        return !unrestricted;
    }

    /**
     * 具体可访问公司 id。
     *
     * <p>不受限时返回<strong>空集合</strong> —— 调用方必须先判 {@link #hasFilter()}；
     * 直接把它拼进 {@code IN ()} 会生成非法 SQL（失败在明处，不会静默放大权限）。
     */
    public Set<Long> ids() {
        return ids;
    }

    /** 是否可访问指定公司；公司归属为空（null）时对受限范围一律拒绝。 */
    public boolean allows(Long companyId) {
        if (unrestricted) {
            return true;
        }
        return companyId != null && ids.contains(companyId);
    }

    @Override
    public String toString() {
        if (unrestricted) {
            return "CompanyScope[unrestricted]";
        }
        if (isDenied()) {
            return "CompanyScope[denied]";
        }
        return "CompanyScope[" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }
}
