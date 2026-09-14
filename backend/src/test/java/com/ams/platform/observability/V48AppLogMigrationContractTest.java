package com.ams.platform.observability;

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
 * V48 迁移的契约守卫（设计 §4.1、§11）。
 *
 * <p>每条断言对应一类真实事故：
 * <ol>
 *   <li>{@code extra} 写成 jsonb —— MyBatis-Plus 实体字段是 String，insert 会类型不匹配
 *       （V4 已把全仓 JSONB 改成 TEXT，这是明确的约定）；</li>
 *   <li>菜单 code / path 与前端 {@code PATH_TO_CODE} 镜像漂移 —— 表现为点菜单落回首页，
 *       或权限判定恒真；与 {@code @RequiresPerm} 的值漂移则接口对所有角色 403；</li>
 *   <li>把 view 回填给所有角色 —— {@code V45 §5.2} 明确把 {@code system.*} 归为敏感菜单，
 *       只授予运维角色；照着 V47（业务导航类菜单）的口径抄就会向全部业务角色敞开运维日志；</li>
 *   <li>误回填报写动作 —— 静默越权，且长期留在库里没人复核（V45 §5.3）。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红，
 * 或让正向断言空转通过。
 */
class V48AppLogMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V48__app_log.sql";

    /** 菜单码：必须与前端 `PATH_TO_CODE['/system/app-logs']` 及后端 @RequiresPerm 逐字一致。 */
    private static final String MENU_CODE = "system.appLog";
    private static final String MENU_PATH = "/system/app-logs";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in = V48AppLogMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V48 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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
    // 建表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("建表包含全部字段，且 extra 用 TEXT 而非 jsonb（V4 全仓口径）")
    void createsAppLogTableWithTextExtra() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS app_log");
        assertThat(sql)
                .as("字段必须齐全：trace_id 是链路关联键，fingerprint 是告警冷却键")
                .contains("trace_id", "level", "app_type", "source", "fingerprint",
                        "message", "extra", "ua", "url", "user_id", "client_ip",
                        "occurred_at", "created_at");
        assertThat(sql)
                .as("extra 必须是 TEXT：MyBatis-Plus 实体字段是 String，jsonb 会在 insert 时类型不匹配")
                .contains("extra       TEXT")
                .doesNotContain("jsonb")
                .doesNotContain("JSONB");
    }

    @Test
    @DisplayName("level / source 有 CHECK 约束；app_type 刻意不加（加一个前端就要改 DDL）")
    void constrainsClosedVocabularies() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("level 是封闭词表，必须由 DB 兜底，否则脏值会让告警/统计的过滤失效")
                .contains("ck_app_log_level")
                .contains("'ERROR'", "'WARN'", "'INFO'");
        assertThat(sql)
                .as("source 是封闭词表，与 AppLogSource 枚举一致")
                .contains("ck_app_log_source")
                .contains("'js'", "'promise'", "'api'", "'backend'");
        assertThat(sql)
                .as("app_type 不加 CHECK：由 Java 侧 AppType 枚举白名单校验，避免加端就改 DDL")
                .doesNotContain("ck_app_log_app_type");
    }

    @Test
    @DisplayName("索引覆盖四类查询：trace 聚合 / 列表排序+清理 / 级别筛选 / 端筛选 / 指纹聚合")
    void createsIndexesForQueryPatterns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("idx_app_log_trace ON app_log (trace_id)");
        assertThat(sql)
                .as("created_at 索引是列表排序与定时清理的扫描路径（清理谓词就是 created_at < ?）")
                .contains("idx_app_log_created ON app_log (created_at DESC)");
        assertThat(sql).contains("idx_app_log_level_time ON app_log (level, created_at DESC)");
        assertThat(sql).contains("idx_app_log_app_time ON app_log (app_type, created_at DESC)");
        assertThat(sql).contains("idx_app_log_fingerprint ON app_log (fingerprint, created_at DESC)");
        assertThat(sql)
                .as("所有索引必须幂等")
                .doesNotContain("CREATE INDEX idx_");
    }

    // ------------------------------------------------------------------
    // 菜单与权限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("菜单行落种子：code / name / type / path / 排序 / 父目录按 code 解析")
    void seedsAppLogMenu() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("菜单 code / path 必须与前端 PATH_TO_CODE 镜像逐字一致，否则点菜单会落回首页")
                .contains("'" + MENU_CODE + "'", "'应用日志'", "'menu'", "'" + MENU_PATH + "'");
        assertThat(sql)
                .as("父目录必须按 code 解析（d.code = 'system'），不得硬编码 parent_id")
                .contains("WHERE d.code = 'system'")
                .doesNotContain("parent_id) VALUES");
        assertThat(sql)
                .as("排序 40：落在 system.role(10) / system.menu(20) / system.dict(30) 之后")
                .contains(", 40, d.id");
        assertThat(sql)
                .as("菜单种子必须幂等，重复执行不报错")
                .contains("ON CONFLICT (code) DO NOTHING");
    }

    @Test
    @DisplayName("view 只回填给 operator：system.* 是敏感菜单，不向业务角色敞开（V45 §5.2）")
    void backfillsViewOnlyForOperator() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("回填语句必须写 role_permission(role_id, menu_id, menu_code, action)")
                .contains("INSERT INTO role_permission (role_id, menu_id, menu_code, action)");
        assertThat(sql)
                .as("必须只回填 view")
                .contains("m.code, 'view'");
        assertThat(sql)
                .as("回填范围只限 operator：app_log 含 URL/UA/自报 ID/堆栈，属运维数据")
                .contains("r.code = 'operator'");
        assertThat(sql)
                .as("不得照抄 V47 的「所有非超管角色」口径 —— 那是业务导航类菜单的写法")
                .doesNotContain("r.code <> 'super_admin'");
        assertThat(sql)
                .as("回填范围必须限定在本菜单，不得扫全表")
                .contains("m.code = '" + MENU_CODE + "'");
        assertThat(sql)
                .as("动作级回填只允许 view：出现任何其它动作词即为静默越权（V45 §5.3）")
                .doesNotContain("'create'", "'update'", "'delete'", "'export'", "'import'", "'approve'",
                        "'audit'", "'assign'");
        assertThat(sql)
                .as("回填必须幂等，重跑不产生重复授权")
                .contains("ON CONFLICT DO NOTHING");
    }

    @Test
    @DisplayName("不触碰业务表：一张新表 + 两条 INSERT，无业务 DDL/DML")
    void touchesNoBusinessTables() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("只新建 app_log，不得改动既有表结构")
                .doesNotContain("ALTER TABLE", "DROP TABLE", "DROP INDEX");
        assertThat(sql)
                .as("不得改动审计表、菜单表数据以外的任何业务数据")
                .doesNotContain("INSERT INTO operation_log", "UPDATE operation_log")
                .doesNotContain("UPDATE menu", "DELETE FROM menu")
                .doesNotContain("UPDATE role", "DELETE FROM role")
                .doesNotContain("UPDATE role_permission", "DELETE FROM role_permission");
        assertThat(sql.split("INSERT INTO", -1))
                .as("只允许两条 INSERT：菜单行 + view 回填")
                .hasSize(3);
    }
}
