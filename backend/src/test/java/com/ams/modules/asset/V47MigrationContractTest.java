package com.ams.modules.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V47 迁移的契约守卫（设计 §7）。
 *
 * <p>本迁移只做两件事：种一行「项目分区管理」菜单、把它的 view 授权回填给非超管角色。
 * 这三条断言分别对应三类真实事故：
 * <ol>
 *   <li>菜单行的 code / path 与前端 {@code PATH_TO_CODE} 镜像漂移 —— 表现为点菜单落回首页，
 *       或权限判定恒真（镜像缺条目时 {@code canByPath} 放行）；</li>
 *   <li>漏回填 view —— 除超管外所有角色看不到新入口，功能表现为「没做出来」；
 *       反向误回填写动作 —— 静默越权，且长期留在库里没人复核（V45 §5.3 明令禁止）；</li>
 *   <li>把业务表 DML 混进菜单迁移 —— 合并冲突或复制粘贴的典型产物。</li>
 * </ol>
 *
 * <p>刻意断言**具体串**而不是整段 SQL：注释调整不该误报，而「扫全表回填」「硬编码 parent_id」
 * 这类写法必须变红。
 */
class V47MigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V47__project_zone_menu.sql";

    /** 本页菜单码：必须与前端 `PATH_TO_CODE['/project-zones']` 逐字一致。 */
    private static final String MENU_CODE = "asset.projectZone";
    private static final String MENU_PATH = "/project-zones";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in = V47MigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V47 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("菜单行按设计 §3.1 落种子：code / name / type / path / 排序 / 父目录")
    void seedsProjectZoneMenu() {
        // 类型断言必须带引号（`'menu'`）：裸 `"menu"` 会被 `INSERT INTO menu`、`menu_type`、
        // `menu_code` 一并满足，于是把第 3 个 SELECT 字面量改成 'dir' 测试依然全绿 ——
        // 而 `@DisplayName` 声称它守住了「type」。这是本任务唯一一处真正会漏事的断言。
        assertThat(SQL)
                .as("必须插入 asset.projectZone 菜单行，且路径与名称与设计一致")
                .contains("'" + MENU_CODE + "'", "'项目分区管理'", "'menu'", "'" + MENU_PATH + "'");
        // 父目录由 code 解析，不能硬编码 parent_id（V45 的 id 在其它环境不保证一致）
        assertThat(SQL)
                .as("父目录必须按 code 解析（d.code = 'asset'），不得硬编码 parent_id")
                .contains("WHERE d.code = 'asset'")
                .doesNotContain("parent_id) VALUES");
        // 排序 15：落在「项目管理」(10) 与「资产台账」(20) 之间
        assertThat(SQL)
                .as("排序必须是 15，插在项目管理(10) 与资产台账(20) 之间")
                .contains(", 15, d.id");
        assertThat(SQL)
                .as("菜单种子必须幂等，重复执行不报错")
                .contains("ON CONFLICT (code) DO NOTHING");
    }

    @Test
    @DisplayName("view 回填：只回填 view、排除 super_admin、范围只限本菜单")
    void backfillsViewForNonSuperAdmin() {
        assertThat(SQL)
                .as("回填语句必须写 role_permission(role_id, menu_id, menu_code, action)")
                .contains("INSERT INTO role_permission (role_id, menu_id, menu_code, action)");
        assertThat(SQL)
                .as("必须只回填 view")
                .contains("m.code, 'view'");
        assertThat(SQL)
                .as("必须排除 super_admin（它走 isSuperAdmin 旁路，不需要数据行）")
                .contains("r.code <> 'super_admin'");
        assertThat(SQL)
                .as("回填范围必须限定在本菜单，不得扫全表")
                .contains("m.code = '" + MENU_CODE + "'");
        assertThat(SQL)
                .as("写动作一律不回填（V45 §5.3）：出现带引号的 create/update/delete 即为越权回填")
                .doesNotContain("'create'", "'update'", "'delete'");
        assertThat(SQL)
                .as("回填必须幂等，重跑不产生重复授权")
                .contains("ON CONFLICT DO NOTHING");
    }

    @Test
    @DisplayName("不触碰业务表与既有菜单：无 DDL，且只允许两条 INSERT")
    void touchesNoBusinessTables() {
        assertThat(SQL)
                .as("本迁移只种菜单，不做任何 DDL")
                .doesNotContain("ALTER TABLE", "CREATE TABLE", "DROP TABLE", "CREATE INDEX", "DROP INDEX");
        assertThat(SQL)
                .as("不得改动分区 / 项目 / 资产数据")
                .doesNotContain("project_zone", "INSERT INTO project", "UPDATE project", "DELETE FROM project")
                .doesNotContain("INTO asset", "UPDATE asset", "DELETE FROM asset")
                .doesNotContain("menu_type = 'menu'");
        assertThat(SQL.split("INSERT INTO", -1))
                .as("只允许两条 INSERT：菜单行 + view 回填")
                .hasSize(3);
    }
}
