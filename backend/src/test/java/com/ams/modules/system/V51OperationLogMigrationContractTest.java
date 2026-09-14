package com.ams.modules.system;

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
 * V51 迁移的契约守卫（设计 §4、§12）。
 *
 * <p>每条断言对应一类真实事故：
 * <ol>
 *   <li>{@code success} 写成 {@code NOT NULL DEFAULT true} —— 存量行会被<b>谎报为成功</b>，
 *       这是审计里最不能出错的方向；</li>
 *   <li>菜单 code / path 与前端 {@code PATH_TO_CODE} 镜像或后端 {@code @RequiresPerm} 漂移 ——
 *       表现为点菜单落回首页，或接口对所有角色 403；</li>
 *   <li>把 view 回填给所有角色 —— {@code V45 §5.2} 把 {@code system.*} 归为敏感菜单，
 *       只授予运维角色；</li>
 *   <li>误回填报写动作（尤其 {@code delete}）—— 本模块纯只读，
 *       用户故事地图明确「操作日志不可删」，NFR-DSEC-016 要求保留 ≥3 年。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红，
 * 或让正向断言空转通过。
 */
class V51OperationLogMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V51__operation_log_query.sql";

    /** 菜单码：必须与前端 `PATH_TO_CODE['/system/operation-logs']` 及后端 @RequiresPerm 逐字一致。 */
    private static final String MENU_CODE = "system.operationLog";
    private static final String MENU_PATH = "/system/operation-logs";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in = V51OperationLogMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V51 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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

    // ------------------------------------------------------------------
    // 加列
    // ------------------------------------------------------------------

    @Test
    @DisplayName("operation_log 加 success / error 两列，且两列都幂等（IF NOT EXISTS）")
    void addsSuccessAndErrorColumns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("ALTER TABLE operation_log ADD COLUMN IF NOT EXISTS success BOOLEAN");
        assertThat(sql).contains("ALTER TABLE operation_log ADD COLUMN IF NOT EXISTS error VARCHAR(500)");
    }

    @Test
    @DisplayName("success 必须可空：存量行是「未知」，默认 true 会把失败的操作谎报为成功")
    void successIsNullable() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("不得出现 NOT NULL —— V51 之前的行当时没记成败，NULL = 未知")
                .doesNotContain("success BOOLEAN NOT NULL");
        assertThat(sql)
                .as("不得出现 DEFAULT TRUE —— 存量行会被静默当成成功")
                .doesNotContain("DEFAULT TRUE")
                .doesNotContain("DEFAULT true");
    }

    // ------------------------------------------------------------------
    // 索引
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两条复合索引落到等值筛选维度 + created_at DESC，且幂等")
    void createsIndexes() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("module 是审计页最常用的等值筛选维度，且要按时间倒序翻页")
                .contains("CREATE INDEX IF NOT EXISTS idx_operation_log_module_created")
                .contains("ON operation_log (module, created_at DESC)");
        assertThat(sql)
                .as("登录日志按 result 等值筛选 + 时间倒序")
                .contains("CREATE INDEX IF NOT EXISTS idx_login_log_result_created")
                .contains("ON login_log (result, created_at DESC)");
    }

    // ------------------------------------------------------------------
    // 菜单与权限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("菜单行落种子：code / name / type / path / 排序 / 父目录按 code 解析")
    void seedsOperationLogMenu() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("菜单 code / path 必须与前端 PATH_TO_CODE 镜像逐字一致，否则点菜单会落回首页")
                .contains("'" + MENU_CODE + "'", "'操作日志'", "'menu'", "'" + MENU_PATH + "'");
        assertThat(sql)
                .as("父目录必须按 code 解析（d.code = 'system'），不得硬编码 parent_id")
                .contains("WHERE d.code = 'system'")
                .doesNotContain("parent_id) VALUES");
        assertThat(sql)
                .as("排序 50：落在 system.role(10) / system.menu(20) / system.dict(30) / system.appLog(40) 之后")
                .contains(", 50, d.id");
        assertThat(sql)
                .as("菜单种子必须幂等，重复执行不报错")
                .contains("ON CONFLICT (code) DO NOTHING");
    }

    @Test
    @DisplayName("view 只回填给 operator：审计数据按 system.* 敏感菜单口径处理（V45 §5.2）")
    void backfillsViewOnlyForOperator() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("回填语句必须写 role_permission(role_id, menu_id, menu_code, action)")
                .contains("INSERT INTO role_permission (role_id, menu_id, menu_code, action)");
        assertThat(sql)
                .as("必须只回填 view")
                .contains("m.code, 'view'");
        assertThat(sql)
                .as("回填范围只限 operator：operation_log 含用户名/IP/入参，属审计数据")
                .contains("r.code = 'operator'");
        assertThat(sql)
                .as("不得照抄 V47 的「所有非超管角色」口径 —— 那是业务导航类菜单的写法")
                .doesNotContain("r.code <> 'super_admin'");
        assertThat(sql)
                .as("回填范围必须限定在本菜单，不得扫全表")
                .contains("m.code = '" + MENU_CODE + "'");
        assertThat(sql)
                .as("动作级回填只允许 view：本模块纯只读，出现 delete 即为静默越权（V45 §5.3）")
                .doesNotContain("'create'", "'update'", "'delete'", "'export'", "'import'", "'approve'",
                        "'audit'", "'assign'");
        assertThat(sql)
                .as("回填必须幂等，重跑不产生重复授权")
                .contains("ON CONFLICT DO NOTHING");
    }

    // ------------------------------------------------------------------
    // 边界：不碰业务数据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不触碰业务表，也不提供任何删除入口：一张表加两列 + 两条索引 + 两条 INSERT")
    void touchesNoBusinessTables() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("不得新建表、不得删表/删索引")
                .doesNotContain("CREATE TABLE", "DROP TABLE", "DROP INDEX", "DROP COLUMN");
        assertThat(sql)
                .as("审计记录只能追加，迁移里不得出现任何 DELETE")
                .doesNotContain("DELETE FROM");
        assertThat(sql)
                .as("不得改动 operation_log / login_log 的既有数据")
                .doesNotContain("INSERT INTO operation_log", "UPDATE operation_log")
                .doesNotContain("INSERT INTO login_log", "UPDATE login_log");
        assertThat(sql)
                .as("不得改动菜单、角色数据以外的任何业务数据")
                .doesNotContain("UPDATE menu", "DELETE FROM menu")
                .doesNotContain("UPDATE role", "DELETE FROM role")
                .doesNotContain("UPDATE role_permission", "DELETE FROM role_permission");
        assertThat(sql.split("INSERT INTO", -1))
                .as("只允许两条 INSERT：菜单行 + view 回填")
                .hasSize(3);
    }

    @Test
    @DisplayName("只碰三张表：operation_log / login_log / menu + role_permission，不误伤他表")
    void touchesOnlyExpectedTables() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("加列只允许出现在 operation_log 上")
                .doesNotContain("ALTER TABLE login_log")
                .doesNotContain("ALTER TABLE menu");
        assertThat(sql)
                .as("迁移动的是 audit 两张表 + 菜单表，不得出现业务表名")
                .doesNotContain("asset_", "contract_", "billing_", "finance_", "project_zone");
    }
}
