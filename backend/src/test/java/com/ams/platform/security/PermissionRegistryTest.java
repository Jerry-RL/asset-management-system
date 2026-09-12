package com.ams.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 启动扫描台账的回归测试（设计 4.5 / 6.1）。
 *
 * <h2>为什么必须有一条「非空」断言</h2>
 * 台账为空时本类所有断言都会因为「对空集合断言」而变成同义反复。历史缺陷正是这个形态：
 * 扫描用了 {@code AopUtils.getTargetClass(Class)}，而它对 Spring 的 CGLIB 代理类返回
 * {@code java.lang.Class}，于是遍历到的是代理生成的覆盖方法（不带注解），
 * 台账恒为空集且<strong>不报任何错</strong>——
 * <ul>
 *   <li>{@code assertMenuCodesExist} 变成「对空集合校验」，那道「宁可启动失败」的
 *       菜单编码防错闸门完全失效；</li>
 *   <li>前端矩阵把所有动作标成「未生效」，管理员据此误判哪些接口已经拦截。</li>
 * </ul>
 * 因此这里断言的是「扫到的数量与关键编码」，而不是「没有非法编码」—— 后者在空集上恒真。
 *
 * <p>测试 profile 关闭了 Flyway（H2 无 schema），因此 {@code assertMenuCodesExist} 会走
 * 「读不到 menu 表则告警放行」的分支；但<strong>扫描本身不依赖数据库</strong>，
 * 所以本用例仍然能锁住上面那个缺陷。
 */
@SpringBootTest
@ActiveProfiles("test")
class PermissionRegistryTest {

    /** 实测首批接入 57 个码；用下限而不是等值，避免后续接入新模块时无谓地改测试。 */
    private static final int EXPECTED_MIN = 40;

    @Autowired
    private PermissionRegistry registry;

    @Test
    @DisplayName("启动扫描必须真的扫到注解（空台账会让启动校验与前端「已生效」标记一起静默失效）")
    void scanFindsAnnotations() {
        Set<String> enforced = registry.enforcedPermissions();

        assertThat(enforced)
                .as("台账为空说明扫描失配（例如代理类未正确解包），而非「没有注解」")
                .isNotEmpty()
                .hasSizeGreaterThan(EXPECTED_MIN);
        assertThat(enforced).contains(
                "asset.ledger:view",
                "asset.ledger:delete",
                "asset.project:create",
                "contract.ledger:approve",
                "org.user:view",
                "system.menu:delete",
                "system.role:assign");
    }

    @Test
    @DisplayName("台账内每一项都是 menuCode:action，且动作取自词表")
    void everyEntryIsWellFormed() {
        for (String permission : registry.enforcedPermissions()) {
            int idx = permission.lastIndexOf(':');
            assertThat(idx).as("缺少冒号：%s", permission).isGreaterThan(0);
            assertThat(PermissionAction.isValid(permission.substring(idx + 1)))
                    .as("动作不在词表内：%s", permission)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("默认档不高报：只下发注解里真实存在的码，变更类动作不会被凭空加入")
    void defaultModeDoesNotOverReport() {
        // 默认档（strict-perm=false）下，未标注的变更类接口并没有被拦截，
        // 因此不能把它们标成「已生效」，否则管理员以为已经拦住了、实际裸奔。
        List<String> effective = registry.effectiveEnforced(List.of("asset.ledger"));

        assertThat(effective).contains("asset.ledger:view");
        assertThat(effective).doesNotContain("asset.ledger:import", "asset.ledger:audit");
        assertThat(effective)
                .as("默认档下下发内容应与台账一致")
                .containsExactlyInAnyOrderElementsOf(registry.enforcedPermissions());
    }
}
