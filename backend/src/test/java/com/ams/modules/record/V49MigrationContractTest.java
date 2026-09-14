package com.ams.modules.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V49 迁移的契约守卫（成本信息 / 费用明细 / 评估信息）。
 *
 * <p>与 {@code V46MigrationContractTest} 同构：Flyway 在测试 profile 下关闭，没有运行期校验
 * 能证明表真的建出来了，因此对迁移文本做结构化断言，抓的是「合并冲突丢一行」「表名/列名
 * 与实体不一致」这类事故。
 */
class V49MigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V49__record_cost_evaluation.sql";

    private static final List<String> TABLES = List.of(
            "biz_cost_record", "biz_cost_item", "biz_evaluation_info");

    /** 每张表的**完整列清单**（{@code 列名:声明}）。 */
    private static final Map<String, List<String>> EXPECTED_COLUMNS = Map.of(
            "biz_cost_record", List.of(
                    "id:BIGSERIAL PRIMARY KEY",
                    "owner_type:VARCHAR(20) NOT NULL",
                    "owner_id:BIGINT NOT NULL",
                    "amount_wan:NUMERIC(18,2)",
                    "cost_date:DATE",
                    "remark:VARCHAR(500)",
                    "created_at:TIMESTAMPTZ NOT NULL DEFAULT now()",
                    "updated_at:TIMESTAMPTZ",
                    "created_by:BIGINT",
                    "updated_by:BIGINT",
                    "deleted_at:TIMESTAMPTZ"),
            "biz_cost_item", List.of(
                    "id:BIGSERIAL PRIMARY KEY",
                    "cost_id:BIGINT NOT NULL",
                    "fee_name:VARCHAR(200)",
                    "cost_type:VARCHAR(50)",
                    "amount:NUMERIC(18,2)",
                    "remark:VARCHAR(500)",
                    "sort:INTEGER NOT NULL DEFAULT 0",
                    "created_at:TIMESTAMPTZ NOT NULL DEFAULT now()",
                    "updated_at:TIMESTAMPTZ",
                    "created_by:BIGINT",
                    "updated_by:BIGINT",
                    "deleted_at:TIMESTAMPTZ"),
            "biz_evaluation_info", List.of(
                    "id:BIGSERIAL PRIMARY KEY",
                    "owner_type:VARCHAR(20) NOT NULL",
                    "owner_id:BIGINT NOT NULL",
                    "institution:VARCHAR(200)",
                    "asset_value:NUMERIC(18,2)",
                    "rent_unit_price:NUMERIC(18,2)",
                    "rent_price:NUMERIC(18,2)",
                    "evaluate_date:DATE",
                    "valid_from:DATE",
                    "valid_to:DATE",
                    "created_at:TIMESTAMPTZ NOT NULL DEFAULT now()",
                    "updated_at:TIMESTAMPTZ",
                    "created_by:BIGINT",
                    "updated_by:BIGINT",
                    "deleted_at:TIMESTAMPTZ"));

    private static final Pattern DELETED_AT_COLUMN =
            Pattern.compile("(?m)^\\s*deleted_at\\s+TIMESTAMPTZ\\s*$");

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in = V49MigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V49 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String tableBody(String table) {
        int start = SQL.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        assertThat(start).as(table + " 必须被创建").isGreaterThanOrEqualTo(0);
        int end = SQL.indexOf("\n);", start);
        assertThat(end).as(table + " 的建表语句必须正常闭合").isGreaterThan(start);
        return SQL.substring(start, end);
    }

    private static Pattern columnPattern(String column, String declaration) {
        String type = Arrays.stream(declaration.trim().split("\\s+"))
                .map(Pattern::quote)
                .collect(Collectors.joining("\\s+"));
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(column) + "\\s+" + type + "(?=\\s|,|;|$)");
    }

    @Test
    @DisplayName("三张新表都以 IF NOT EXISTS 建出，保证迁移可重复执行")
    void createsAllTablesIdempotently() {
        assertThat(SQL).contains(TABLES.stream()
                .map(table -> "CREATE TABLE IF NOT EXISTS " + table)
                .toArray(String[]::new));
        assertThat(EXPECTED_COLUMNS.keySet()).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    @DisplayName("三张表的每一列都按设计声明（列名 + 类型），合并冲突丢一行即红")
    void declaresEveryColumnWithItsDeclaredType() {
        EXPECTED_COLUMNS.forEach((table, specs) -> {
            String body = tableBody(table);
            assertThat(specs).as("%s 的期望列清单不能为空", table).isNotEmpty();
            for (String spec : specs) {
                int sep = spec.indexOf(':');
                assertThat(sep).as("期望项必须是「列名:声明」形式: %s", spec).isGreaterThan(0);
                String column = spec.substring(0, sep);
                String declaration = spec.substring(sep + 1);
                assertThat(body)
                        .as("%s 必须声明列 %s %s", table, column, declaration)
                        .containsPattern(columnPattern(column, declaration));
            }
        });
    }

    @Test
    @DisplayName("记录表都有 deleted_at TIMESTAMPTZ 列，否则软删无处落值")
    void everyRecordTableHasDeletedAt() {
        for (String table : TABLES) {
            assertThat(tableBody(table))
                    .as("%s 必须有 deleted_at TIMESTAMPTZ 列", table)
                    .containsPattern(DELETED_AT_COLUMN);
        }
    }

    @Test
    @DisplayName("字典：成本类型以幂等方式落种子")
    void seedsCostTypeDictionary() {
        assertThat(SQL)
                .contains("('cost_type', '成本类型'")
                .contains("('cost_type', 'decoration'")
                .contains("('cost_type', 'maintenance'")
                .contains("('cost_type', 'other'")
                .contains("ON CONFLICT (code) DO NOTHING")
                .contains("ON CONFLICT (type_id, value) DO NOTHING");
    }
}
