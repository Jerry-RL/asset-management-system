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
 * V52 迁移的契约守卫（设计 §4.3、§7.3）。
 *
 * <p>V52 是纯 expand：只给 {@code operation_log} 加一条「按被操作对象查询」的复合索引。
 * 因此这里的断言集中在两件事上：
 *
 * <ol>
 *   <li><b>索引形状</b>：列顺序必须是「等值列 → 排序列」，且排序列与
 *       {@code AuditLogService.query} 的 {@code ORDER BY created_at DESC, id DESC} 一致。
 *       顺序写错（例如把 created_at 放前面）不会报错，只会让「按对象查最近的操作」退化成全表扫描
 *       —— 一个只在数据量涨起来之后才暴露的性能问题；</li>
 *   <li><b>不越界</b>：不碰既有列、不插菜单、不回填权限、不动业务数据。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释后的结果）：注释里说「只加索引」而语句里
 * 顺手改了列类型，是这类迁移最典型的事故，负向断言必须看真实语句。
 */
class V52OperationLogRefIdIndexContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V52__operation_log_ref_id_index.sql";

    private static final String INDEX_NAME = "idx_operation_log_ref_created";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in = V52OperationLogRefIdIndexContractTest.class
                .getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V52 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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
    @DisplayName("索引列顺序为 ref_id 等值列 + created_at DESC, id DESC 排序列，且幂等")
    void createsCompositeIndexInTheRightOrder() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("索引必须幂等：迁移重跑不得报错")
                .contains("CREATE INDEX IF NOT EXISTS " + INDEX_NAME);
        assertThat(sql)
                .as("列顺序必须是「等值列 → 排序列」：只建 ref_id 单列索引的话，"
                        + "命中后仍要按 created_at 重排，翻页越深越慢")
                .contains("ON operation_log (ref_id, created_at DESC, id DESC)");
        assertThat(sql)
                .as("id 必须进索引：排序是 created_at DESC, id DESC，"
                        + "不一致会让索引无法直接供排序使用（同秒多条时靠 id 兜底，否则翻页漏行）")
                .contains("id DESC");
    }

    @Test
    @DisplayName("纯 expand：不碰既有列、不插菜单、不回填权限、不动业务数据")
    void isExpandOnly() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("V52 只加索引，不得改列 / 删列 / 建表")
                .doesNotContain("ALTER COLUMN", "DROP COLUMN", "ADD COLUMN", "ALTER TABLE")
                .doesNotContain("CREATE TABLE", "DROP TABLE", "DROP INDEX");
        assertThat(sql)
                .as("索引只能建在 operation_log 上：ref_id 是本表的列")
                .doesNotContain("ON login_log", "ON menu", "ON role_permission");
        assertThat(sql)
                .as("不得插菜单 / 回填权限（那是 V51 的职责，重复一次会产生重复授权）")
                .doesNotContain("INSERT INTO", "UPDATE ", "DELETE FROM");
        assertThat(sql)
                .as("审计记录只可追加：迁移里不得出现任何写入")
                .doesNotContain("operation_log SET");
    }

    @Test
    @DisplayName("不改动审计数据本身：只允许 COMMENT ON INDEX，不得 COMMENT 到数据列")
    void commentsOnlyTheIndex() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("给索引加注释是为了让 DBA 知道它为什么存在")
                .contains("COMMENT ON INDEX " + INDEX_NAME);
        assertThat(sql)
                .as("不得给 operation_log 的列写注释：那属于 V51 的范围，重复定义会互相覆盖")
                .doesNotContain("COMMENT ON COLUMN");
    }
}
