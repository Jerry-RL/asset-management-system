package com.ams.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 严格档（{@code ams.security.strict-perm=true}）下的台账口径（设计 D17）。
 *
 * <p>严格档会把<strong>所有变更类接口</strong>一并拒绝，无论有没有 {@code @RequiresPerm}。
 * 此时矩阵若仍按注解清单标记「未生效」，管理员会去补一个早已生效的注解 ——
 * 这是「低报」，与默认档的「高报」是同一个错误的两个方向。
 *
 * <p>单独一个测试类是因为它需要一个 strict-perm=true 的容器，与默认档上下文不同。
 */
@SpringBootTest(properties = "ams.security.strict-perm=true")
@ActiveProfiles("test")
class PermissionRegistryStrictPermTest {

    @Autowired
    private PermissionRegistry registry;

    @Test
    @DisplayName("严格档：参与矩阵的菜单码会补齐全部变更类动作")
    void strictModeExpandsMutatingActions() {
        List<String> effective = registry.effectiveEnforced(List.of("asset.ledger"));

        // 变更类动作整体计入：这些码即便没有注解也已经被拦住了
        assertThat(effective).contains("asset.ledger:create", "asset.ledger:update",
                "asset.ledger:delete", "asset.ledger:approve", "asset.ledger:assign");
        // 只读动作不在严格档的自动补集里，仍然以注解台账为准
        assertThat(effective).contains("asset.ledger:view");
        // 未参与矩阵的菜单码不会被展开成变更类动作。
        // 注意不能拿 system.menu:create 来断言 —— 它本身有 @RequiresPerm，属于注解台账，
        // 无论是否参与矩阵都会下发。只有「没有注解 + 不在矩阵里」才证明没有凭空展开。
        assertThat(effective).doesNotContain("system.role:create");
    }

    @Test
    @DisplayName("严格档：仍包含全部注解台账（严格档是叠加，不是替换）")
    void strictModeIsAdditive() {
        assertThat(registry.effectiveEnforced(List.of()))
                .containsExactlyInAnyOrderElementsOf(registry.enforcedPermissions());
    }

    @Test
    @DisplayName("严格档：空/含 null 的菜单码列表不应抛错，也不应凭空产生条目")
    void strictModeIsNullSafe() {
        assertThat(registry.effectiveEnforced(null))
                .containsExactlyInAnyOrderElementsOf(registry.enforcedPermissions());
        assertThat(registry.effectiveEnforced(java.util.Arrays.asList("asset.ledger", null)))
                .isNotEmpty();
    }
}
