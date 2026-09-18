package com.ams.modules.assetmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V61 迁移的契约守卫（「资产经营管理」目录 + 7 个子模块菜单）。
 *
 * <p>本次只落**菜单骨架**（功能待迭代），因此断言集中在「菜单树挂得对不对」，
 * 每条对应一类真实事故：
 *
 * <ol>
 *   <li>子菜单没挂在 `assetmgmt` 下（`parent_id` 写成标量子查询会得到 NULL，
 *       菜单变成没有上级的一级目录，侧边栏直接跳过它 —— 表现为「菜单凭空消失」且无报错）；</li>
 *   <li>path 与前端 `PATH_TO_CODE` 镜像漂移 —— 点菜单落回首页，或该页按钮门槛静默失效；</li>
 *   <li>code 没有沿用「目录 code + '.' + 页面名」的分层约定 —— 权限矩阵里看不出归属；</li>
 *   <li>漏了 view 回填 —— 除超管外所有人看不到入口，新功能表现为「没做出来」；</li>
 *   <li>误回填写动作 —— 安静的越权，且会长期留在库里没人复核。</li>
 * </ol>
 *
 * <p><b>本迁移刻意不建业务表、不加 `@RequiresPerm`</b>（规划页是只读说明页，没有接口），
 * 因此下面的「结构不变量」用例把这一点也钉住：一旦有人顺手在本迁移里建表或加权限注解，
 * 用例会变红并提示改走新迁移。
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 */
class V61AssetMgmtMenuMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V61__asset_mgmt_menu.sql";

    private static final String DIR_CODE = "assetmgmt";

    /** 7 个子菜单：code / 名称 / path / sort。顺序即业务顺序，改动前请确认产品意图。 */
    private static final List<String[]> MENUS = List.of(
            new String[] {"assetmgmt.leaseListing", "资产招租管理", "/asset-mgmt/lease-listing", "10"},
            new String[] {"assetmgmt.leaseSigning", "资产租赁签约管理", "/asset-mgmt/lease-signing", "20"},
            new String[] {"assetmgmt.leaseRisk", "资产租赁风险管理", "/asset-mgmt/lease-risk", "30"},
            new String[] {"assetmgmt.resource", "资产资源管理", "/asset-mgmt/resource", "40"},
            new String[] {"assetmgmt.otherUse", "资产其他使用管理", "/asset-mgmt/other-use", "50"},
            new String[] {"assetmgmt.inspection", "资产巡检管理", "/asset-mgmt/inspection", "60"},
            new String[] {"assetmgmt.repair", "资产维修管理", "/asset-mgmt/repair", "70"});

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V61AssetMgmtMenuMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V61 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sqlWithoutComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int idx = line.indexOf("--");
                    return idx >= 0 ? line.substring(0, idx) : line;
                })
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("目录：code=assetmgmt / 名称 / type=dir / 无 path / icon=solution / sort=75")
    void seedsDirectory() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("目录必须落在「资产运营」(70) 与「合同管理」(80) 之间")
                .contains("('assetmgmt', '资产经营管理', 'dir', NULL, 'solution', 75, NULL)");
        assertThat(sql)
                .as("icon 必须是前端 ICON_BY_NAME 里存在的取值，否则会静默退回默认图标")
                .contains("'solution'");
    }

    @Test
    @DisplayName("7 个子菜单：code / 名称 / path / sort 逐条对齐，且顺序为业务流程顺序")
    void seedsAllSevenMenus() {
        String sql = sqlWithoutComments(SQL);

        // 用正则解析而不是 contains 字面量：VALUES 列表为了对齐有多余空格，
        // 逐字 contains 会在「只是调了空格」时误红，而它并不是漂移
        List<String> actual = new ArrayList<>();
        Matcher matcher = Pattern
                .compile("\\('(assetmgmt\\.[\\w]+)',\\s*'([^']+)',\\s*'([^']+)',\\s*(\\d+)\\)")
                .matcher(sql);
        while (matcher.find()) {
            actual.add(matcher.group(1) + "|" + matcher.group(2) + "|" + matcher.group(3)
                    + "|" + matcher.group(4));
        }
        List<String> expected = MENUS.stream()
                .map(menu -> String.join("|", menu))
                .toList();

        assertThat(actual)
                .as("7 条必须逐条解析出来（正则失配会让本断言恒真），且顺序即侧栏顺序 = 业务流程顺序")
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("子菜单挂在 assetmgmt 下：按 code 解析父目录，且不得用标量子查询写 parent_id")
    void menusHangUnderDirectory() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("父目录按 code 定位（不依赖目录名）；用 CROSS JOIN 让目录缺失时插入 0 行")
                .contains("CROSS JOIN menu d")
                .contains("WHERE d.code = 'assetmgmt'");
        assertThat(sql)
                .as("标量子查询在目录不存在时会写入 NULL 的 parent_id，菜单会变成挂不上树的一级目录")
                .doesNotContain("parent_id = (SELECT")
                .doesNotContain("parent_id = (");
    }

    @Test
    @DisplayName("path 与前端 PATH_TO_CODE 镜像逐字一致（漂移会让点菜单落回首页）")
    void pathsMatchFrontendMirror() {
        String sql = sqlWithoutComments(SQL);

        // 镜像文件在同仓的 frontend 下；用相对路径读，跨仓漂移就在后端测试里暴露
        java.nio.file.Path mirror =
                java.nio.file.Paths.get("..", "frontend", "admin-web", "src", "lib", "pathToCode.ts");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(mirror),
                "前端镜像不在工作区（例如只检出 backend 子目录）—— 跳过镜像比对，CI 的 "
                        + "check-perm-invariants 会覆盖这条");

        String mirrorSrc;
        try {
            mirrorSrc = java.nio.file.Files.readString(mirror, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String[] menu : MENUS) {
            assertThat(mirrorSrc)
                    .as("PATH_TO_CODE 必须含 '" + menu[2] + "': '" + menu[0] + "'，"
                            + "否则该页按钮门槛静默失效（不报错、只是本该隐藏的按钮仍然显示）")
                    .contains("'" + menu[2] + "': '" + menu[0] + "'");
        }
    }

    @Test
    @DisplayName("权限回填：非超管角色各 7 条 view，且不含任何写动作")
    void backfillsViewOnly() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("不回填 view 则除超管外所有人看不到入口")
                .contains("INSERT INTO role_permission")
                .contains("m.code LIKE 'assetmgmt.%'")
                .as("导航类菜单用 V45 §5.1 的通用口径（排除 super_admin 即可），"
                        + "不像 V60 那样只给 operator —— 那 7 页不含敏感数据、也没有写操作")
                .contains("r.code <> 'super_admin'");

        assertThat(sql)
                .as("写动作一律不回填（V45 §5.3：动作级回填是上线前置人工步骤）")
                .doesNotContain("'create'")
                .doesNotContain("'update'")
                .doesNotContain("'delete'")
                .doesNotContain("'assign'");
    }

    @Test
    @DisplayName("结构不变量：本次只碰菜单表 —— 不建业务表、不改既有表、不加权限注解")
    void touchesMenuTablesOnly() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("本次交付的是菜单骨架 + 规划页，功能待迭代：顺手建表会让「规划中」与"
                        + "「已实现」在库里混在一起，后续无法安全清理")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("ALTER TABLE")
                .doesNotContain("DROP");
        assertThat(sql)
                .as("规划页是只读说明页、没有接口，因此不该有 @RequiresPerm（那会让 "
                        + "PermissionRegistry 去 menu 表里找一堆用不上的码）")
                .doesNotContain("@RequiresPerm");

        long insertTargets = Pattern.compile("INSERT INTO (\\w+)").matcher(sql).results().count();
        assertThat(insertTargets)
                .as("只允许三条 INSERT：目录行 + 7 个子菜单行（VALUES 一条）+ view 回填（一条）")
                .isEqualTo(3);
        assertThat(sql)
                .as("写的目标表只有 menu 与 role_permission")
                .contains("INSERT INTO menu")
                .contains("INSERT INTO role_permission")
                .doesNotContain("INSERT INTO sys_dict");
    }

    @Test
    @DisplayName("幂等：重复执行迁移不得报错或产生重复行")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("menu.code 上有 uk_menu_code 唯一约束，两处 INSERT 都要 ON CONFLICT DO NOTHING")
                .contains("ON CONFLICT (code) DO NOTHING")
                .contains("ON CONFLICT DO NOTHING");
    }
}
