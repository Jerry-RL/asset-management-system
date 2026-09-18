package com.ams.modules.assetoperator;

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
 * V60 迁移的契约守卫（资产运营人员管理）。
 *
 * <p>每条断言对应一类真实事故：
 *
 * <ol>
 *   <li>三张表少一张 —— MyBatis 会去查一张不存在的表，接口全部 500；</li>
 *   <li>「一人一份有效档案」的唯一索引退化成普通唯一索引 —— 软删后该人员永久占位，
 *       再也登记不出来（这是**部分**唯一索引，`WHERE deleted_at IS NULL` 不能少）；</li>
 *   <li>范围表用三个可空列（project_id / zone_id / asset_id）而不是 `scope_type + scope_id` ——
 *       组合唯一索引里两列恒为 NULL，而 PG 把 NULL 视为互不相等，去重会**静默失效**
 *       （V56 抵押记录已经踩过同一个坑）；</li>
 *   <li>目录改名顺手改了 `menu.code` —— 会同时打断 `role_permission.menu_code` 与
 *       前端 `pathToCode` 镜像（V58 为改名的完整清单立过规矩）；</li>
 *   <li>权限回填按「所有非超管角色」而不是「运维角色」—— 本页能给人登记角色，
 *       属权限面入口，与 `org.user` 同级的敏感菜单。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 */
class V60AssetOperatorMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V60__asset_operator.sql";

    private static final String MAIN_TABLE = "asset_operator";
    private static final String ROLE_TABLE = "asset_operator_role";
    private static final String SCOPE_TABLE = "asset_operator_scope";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V60AssetOperatorMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V60 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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

    /** 建表语句体：从 `CREATE TABLE IF NOT EXISTS <表>` 到配对的 `\n);`。 */
    private static String tableBody(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        if (start == -1) {
            return null;
        }
        int end = sql.indexOf("\n);", start);
        return end == -1 ? null : sql.substring(start, end);
    }

    /** 建表语句里「缩进 4 空格的列声明」的列名，按出现顺序。 */
    private static List<String> columnsOf(String body) {
        Matcher matcher = Pattern.compile("^\\s{4}(\\w+)\\s+[A-Z]", Pattern.MULTILINE).matcher(body);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("三张表都建出来，且列集恰好（主表 9 列 / 角色与范围各 7 列）")
    void createsExactlyThreeTables() {
        String sql = sqlWithoutComments(SQL);

        assertThat(columnsOfTable(sql, MAIN_TABLE))
                .as("列集用「恰好相等」而不是 contains：后者只能证明没少列，证明不了没多列")
                .containsExactly("id", "user_id", "status", "remark",
                        "created_at", "updated_at", "created_by", "updated_by", "deleted_at");
        assertThat(columnsOfTable(sql, ROLE_TABLE))
                .containsExactly("id", "operator_id", "role_id",
                        "created_at", "updated_at", "created_by", "updated_by");
        assertThat(columnsOfTable(sql, SCOPE_TABLE))
                .containsExactly("id", "operator_id", "scope_type", "scope_id",
                        "created_at", "updated_at", "created_by", "updated_by");
    }

    /** 取某张表的列名；表不存在时失败（而不是让后面的断言因为空集合而恒真）。 */
    private static List<String> columnsOfTable(String sql, String table) {
        String body = tableBody(sql, table);
        assertThat(body).as("迁移缺少建表语句：" + table).isNotNull();
        return columnsOf(body);
    }

    @Test
    @DisplayName("「一人一份有效档案」用部分唯一索引：WHERE deleted_at IS NULL 不能少")
    void enforcesOneActiveArchivePerUser() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("少了它同一个人会出现两条档案，界面上看不出哪条生效")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_operator_user")
                .contains("(user_id)");
        assertThat(sql)
                .as("必须是**部分**唯一索引：软删后要允许重新登记，否则该人员永久占位")
                .contains("WHERE deleted_at IS NULL");
    }

    @Test
    @DisplayName("范围用 scope_type + scope_id 两列（而非三个可空列），并有去重唯一索引")
    void scopeUsesTypeAndIdPair() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("三个可空列的组合唯一索引里两列恒 NULL，PG 视为互不相等，去重会静默失效")
                .contains("scope_type")
                .contains("scope_id")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_operator_scope")
                .contains("(operator_id, scope_type, scope_id)");
        assertThat(sql)
                .as("范围明细不带软删列：编辑是全量替换，留软删只会制造永不清理的孤儿行")
                .doesNotContain("ALTER TABLE " + SCOPE_TABLE);
        assertThat(sql)
                .as("角色明细同样要去重")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_operator_role")
                .contains("(operator_id, role_id)");
    }

    @Test
    @DisplayName("角色表不得写 user_role：角色只登记、不授权")
    void neverTouchesUserRoleTable() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("这里同步写 user_role 就等于给业务模块开了一条提权路径，"
                        + "而它的权限码不是提权类权限码")
                .doesNotContain("INSERT INTO user_role")
                .doesNotContain("UPDATE user_role")
                .doesNotContain("DELETE FROM user_role");
    }

    @Test
    @DisplayName("目录只改 name：menu.code 必须仍是 ops（改码会打断权限冗余列与前端镜像）")
    void renamesDirectoryWithoutChangingCode() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("只改名")
                .contains("UPDATE menu SET name = '资产运营人员管理' WHERE code = 'ops'");
        assertThat(sql)
                .as("改 code 会同时打断 role_permission.menu_code 与 pathToCode 镜像（V58 的清单）")
                .doesNotContain("SET code =")
                .doesNotContain("UPDATE menu SET code");
    }

    @Test
    @DisplayName("新菜单挂在 ops 下、sort 5 排在租户管理之前、path 与前端镜像一致")
    void seedsMenuUnderOps() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("path 写错会让点菜单落回首页；前端 PATH_TO_CODE 是 '/asset-operators'")
                .contains("'ops.assetOperator', '资产运营人员管理', 'menu', '/asset-operators'")
                .contains("FROM menu d WHERE d.code = 'ops'")
                .as("sort 5 落在「租户管理」(10) 之前 —— 目录名就是本页名")
                .contains("5, d.id")
                .as("parent_id 用子查询写 NULL 会让菜单变成没有上级的一级目录，侧边栏直接跳过它")
                .doesNotContain("parent_id = (SELECT");
    }

    @Test
    @DisplayName("权限只回填 operator 的 view：本页是权限面入口，不向全部业务角色敞开，也不回填写动作")
    void backfillsViewOnlyForOperator() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("不回填 view 则除超管外没人看得到入口")
                .contains("INSERT INTO role_permission")
                .contains("AND m.code = 'ops.assetOperator'")
                .as("与 org.user 同级的敏感菜单：只给运维角色，不用「<> super_admin」的全量口径")
                .contains("r.code = 'operator'")
                .doesNotContain("r.code <> 'super_admin'");
        assertThat(sql)
                .as("写动作一律不回填（V45 §5.3：动作级回填是上线前置人工步骤）")
                .doesNotContain("'create'")
                .doesNotContain("'update'")
                .doesNotContain("'delete'");
    }

    @Test
    @DisplayName("幂等：重复执行迁移不得报错或产生重复行")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("建表 / 索引 IF NOT EXISTS、菜单 ON CONFLICT DO NOTHING、权限 ON CONFLICT DO NOTHING")
                .contains("CREATE TABLE IF NOT EXISTS")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS")
                .contains("CREATE INDEX IF NOT EXISTS")
                .contains("ON CONFLICT (code) DO NOTHING")
                .contains("ON CONFLICT DO NOTHING");
    }
}
