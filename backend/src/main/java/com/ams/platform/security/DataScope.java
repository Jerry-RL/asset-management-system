package com.ams.platform.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * 数据范围取值（设计 5.1）。
 *
 * <p>集中定义「宽度」的比较规则，供两处使用：登录装配取多角色里最宽的（{@code widen}）、
 * 以及提权约束里禁止把角色改到比调用者更宽（设计 4.5）。
 *
 * <p><strong>本期只实现 all 与 company</strong>：{@code dept} / {@code project} / {@code self}
 * 一律按「所属公司 + 全部下级子树」处理（退化为公司级），见设计第 9 节。把它们仍留在词表里
 * 是为了让既有数据的取值可被解析，而不是宣称已生效 ——
 * {@link #effectiveDescription()} 会如实描述实际生效口径，前端据此提示管理员。
 */
public enum DataScope {

    ALL("all"),
    COMPANY("company"),
    DEPT("dept"),
    PROJECT("project"),
    SELF("self");

    private final String code;

    DataScope(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 宽度序号，越大越宽。 */
    public int rank() {
        return ordinal() + 1;
    }

    public static Optional<DataScope> of(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }

    /** 宽度比较：{@code wider} 是否严格宽于 {@code base}；无法解析的取值按最窄处理。 */
    public static boolean isWiderThan(String wider, String base) {
        return rankOf(wider) > rankOf(base);
    }

    /** 无法解析（null / 未知取值）时按最窄 SELF 处理 —— 收敛方向必须朝安全侧。 */
    public static int rankOf(String code) {
        return of(code).map(DataScope::rank).orElse(SELF.rank());
    }

    /**
     * 实际生效的可见范围描述。
     *
     * <p>只有 {@link #ALL} 与 {@link #COMPANY} 是本期的真实口径，其余三种退化为公司级，
     * 描述里必须写明，避免管理员误以为已经限制了范围。
     */
    public String effectiveDescription() {
        return switch (this) {
            case ALL -> "全部公司（仍受排除清单约束）";
            case COMPANY -> "所属公司 + 全部下级子树";
            case DEPT, PROJECT, SELF ->
                    "本期按公司级生效：所属公司 + 全部下级子树（" + code + " 尚未实现）";
        };
    }
}
