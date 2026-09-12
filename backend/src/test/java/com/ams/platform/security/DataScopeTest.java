package com.ams.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据范围宽度次序的回归锁（设计 5.1）。
 *
 * <p>这组次序是两个安全判断的唯一依据，且**错了不会报错**，只会静默放开：
 * <ul>
 *   <li>登录装配取多角色里最宽的（{@code RbacService}）；次序反向 → 角色的
 *       {@code dataScope=all} 被忽略，且其它取值被降级为 {@code self}；</li>
 *   <li>提权约束禁止把角色改到比调用者更宽（{@code RoleService}）。次序反向 →
 *       「{@code all} 不比 {@code company} 宽」，持 {@code system.role:assign} 的非超管
 *       可以把角色改成 {@code all}，绕过设计里明确要拦的那一步。</li>
 * </ul>
 *
 * <p>历史缺陷：{@code rank()} 曾返回 {@code ordinal() + 1}，而枚举按由宽到窄声明，
 * 于是 {@code all} 的序号最小、{@code self} 最大，恰好与「越大越宽」相反。
 */
class DataScopeTest {

    @Test
    @DisplayName("宽度次序严格递减：all > company > dept > project > self")
    void ranksFollowWidthOrder() {
        assertThat(DataScope.ALL.rank()).isGreaterThan(DataScope.COMPANY.rank());
        assertThat(DataScope.COMPANY.rank()).isGreaterThan(DataScope.DEPT.rank());
        assertThat(DataScope.DEPT.rank()).isGreaterThan(DataScope.PROJECT.rank());
        assertThat(DataScope.PROJECT.rank()).isGreaterThan(DataScope.SELF.rank());
    }

    @Test
    @DisplayName("all 是最宽：宽于其余任何取值")
    void allIsWidest() {
        for (DataScope other : DataScope.values()) {
            if (other == DataScope.ALL) {
                continue;
            }
            assertThat(DataScope.isWiderThan(DataScope.ALL.code(), other.code()))
                    .as("all 应宽于 %s", other.code())
                    .isTrue();
            assertThat(DataScope.isWiderThan(other.code(), DataScope.ALL.code()))
                    .as("%s 不应宽于 all", other.code())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("self 是最窄：不宽于其余任何取值")
    void selfIsNarrowest() {
        for (DataScope other : DataScope.values()) {
            if (other == DataScope.SELF) {
                continue;
            }
            assertThat(DataScope.isWiderThan(DataScope.SELF.code(), other.code()))
                    .as("self 不应宽于 %s", other.code())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("无法解析 / null 的取值按最窄处理（收敛方向朝安全侧，不得被当成 all）")
    void unknownIsNarrowest() {
        assertThat(DataScope.rankOf("not_a_scope")).isEqualTo(DataScope.SELF.rank());
        assertThat(DataScope.rankOf(null)).isEqualTo(DataScope.SELF.rank());

        assertThat(DataScope.isWiderThan("not_a_scope", DataScope.SELF.code())).isFalse();
        assertThat(DataScope.isWiderThan("not_a_scope", DataScope.COMPANY.code())).isFalse();
        // 反向：任何真实取值都「宽于」未知值，因此未知值不会挡住提权判定
        assertThat(DataScope.isWiderThan(DataScope.COMPANY.code(), "not_a_scope")).isTrue();
    }

    @Test
    @DisplayName("提权方向：company 视角下 all 更宽（必须拦住），all 视角下 company 不更宽")
    void escalationDirection() {
        // 调用者只有 company 时，改到 all 属于扩权 → isWiderThan 必须为真
        assertThat(DataScope.isWiderThan(DataScope.ALL.code(), DataScope.COMPANY.code())).isTrue();
        assertThat(DataScope.isWiderThan(DataScope.ALL.code(), DataScope.DEPT.code())).isTrue();
        // 调用者已有 all 时，收窄到 company 不应被拦
        assertThat(DataScope.isWiderThan(DataScope.COMPANY.code(), DataScope.ALL.code())).isFalse();
        // 同宽度不算扩权（幂等保存不得报错）
        assertThat(DataScope.isWiderThan(DataScope.COMPANY.code(), DataScope.COMPANY.code())).isFalse();
    }

    @Test
    @DisplayName("生效口径描述写明本期只实现 all 与 company，其余为降级")
    void effectiveDescriptionIsHonest() {
        assertThat(DataScope.ALL.effectiveDescription()).contains("全部公司");
        assertThat(DataScope.COMPANY.effectiveDescription()).contains("下级");
        assertThat(DataScope.DEPT.effectiveDescription()).contains("尚未实现");
        assertThat(DataScope.PROJECT.effectiveDescription()).contains("尚未实现");
        assertThat(DataScope.SELF.effectiveDescription()).contains("尚未实现");
    }
}
