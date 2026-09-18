package com.ams.modules.disposal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V58 迁移的契约守卫（「资产处置记录」菜单改挂到「资债权证记录」目录）。
 *
 * <p>每条断言对应一类真实事故：
 * <ol>
 *   <li><b>只改 parent_id 不改 code</b> —— 菜单显示在「资债权证记录」下，而权限码仍是
 *       {@code operation.*}，权限矩阵与实际归属长期背离；</li>
 *   <li><b>改了 code 不同步 role_permission.menu_code</b> —— 它是反范式冗余列，
 *       不同步会让「按 menu_id 查权限正常、按 menu_code 查为空」，两种口径给出不同结果
 *       （V54/V55/V57 的 view 回填语句都是按 menu_code 写的）；</li>
 *   <li><b>用标量子查询写 parent_id</b> —— {@code deed} 目录不存在时会写入 NULL，
 *       菜单变成「无上级的 menu 行」，侧边栏生成逻辑直接跳过它，表现为菜单凭空消失且无报错。
 *       必须用 {@code UPDATE ... FROM} 让缺失父目录时更新 0 行。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 */
class V58MoveDisposalRecordMenuMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V58__move_disposal_record_menu.sql";

    private static final String OLD_CODE = "operation.disposalRecord";
    private static final String NEW_CODE = "deed.disposalRecord";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in = V58MoveDisposalRecordMenuMigrationContractTest.class
                .getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V58 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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
    @DisplayName("菜单改名 + 改挂 deed 目录 + 重排 sort 47，三件事在同一句 UPDATE 里")
    void movesMenuToDeedDirectory() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("只改挂载位置不改 code，会让权限码停留在 operation.*（权限矩阵与归属背离）")
                .contains("SET code = '" + NEW_CODE + "'")
                .contains("parent_id = d.id")
                .as("sort 47 落在「资产调拨记录」(46) 之后")
                .contains("sort = 47");
        assertThat(sql)
                .as("必须按旧码定位，且 JOIN deed 目录")
                .contains("WHERE m.code = '" + OLD_CODE + "'")
                .contains("d.code = 'deed'");
    }

    @Test
    @DisplayName("parent_id 用 UPDATE ... FROM 而非标量子查询：deed 缺失时更新 0 行，而不是写入 NULL")
    void usesJoinInsteadOfScalarSubquery() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("UPDATE ... FROM 是「父目录缺失即空操作」的实现方式")
                .contains("UPDATE menu m")
                .contains("FROM menu d");
        assertThat(sql)
                .as("标量子查询在父目录缺失时会写入 NULL，菜单会静默消失")
                .doesNotContain("parent_id = (SELECT");
    }

    @Test
    @DisplayName("role_permission.menu_code 同步改名，且不动 menu_id（菜单行本身没换）")
    void syncsDenormalizedMenuCode() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("menu_code 是反范式冗余列；不同步则按码查询得到空集")
                .contains("UPDATE role_permission")
                .contains("SET menu_code = '" + NEW_CODE + "'")
                .contains("WHERE menu_code = '" + OLD_CODE + "'");
        assertThat(sql)
                .as("menu_id 不能动：菜单行本身没换，改它会让 uk_role_menu_action 撞唯一约束")
                .doesNotContain("SET menu_id")
                .doesNotContain("menu_id =");
    }

    @Test
    @DisplayName("不新增权限行、不改写动作：本次只是改名，授权范围完全不变")
    void doesNotTouchPermissions() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("改名不该顺带授权（那是 V45 §5.3 列为上线前置的人工步骤）")
                .doesNotContain("INSERT INTO role_permission")
                .doesNotContain("'create'")
                .doesNotContain("'update'")
                .doesNotContain("'delete'");
    }

    @Test
    @DisplayName("幂等：两条语句都以旧码为条件，重跑即为空操作")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        // 重跑时旧码已不存在 → 两条 UPDATE 都更新 0 行；menu.code 的唯一约束保证不会撞码
        int updateCount = sql.split("UPDATE ", -1).length - 1;
        assertThat(updateCount)
                .as("恰好两条 UPDATE（menu 改挂 / role_permission 同步），且没有别的写语句")
                .isEqualTo(2);
        assertThat(sql)
                .as("不得出现 INSERT / DELETE —— 改名是纯 UPDATE 操作")
                .doesNotContain("INSERT INTO")
                .doesNotContain("DELETE FROM")
                .doesNotContain("ALTER TABLE")
                .doesNotContain("CREATE TABLE");
    }
}
