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
 * V46 迁移的契约守卫（设计 §4.1 / §4.2 / 设计 §5.2 的字段口径）。
 *
 * <p>Flyway 在测试 profile 下是关闭的（{@code ams_test} 用 H2、{@code flyway.enabled=false}），
 * 因此**没有**运行期校验能证明这些表真的建出来了。本类改为对迁移文本做结构化断言：
 * 它抓的是「合并冲突时被误删一行」「表名/列名与实体不一致」这类真实事故，
 * 而不是「SQL 语法是否正确」（那需要数据库，交给部署时的 Flyway）。
 *
 * <p>刻意断言的是**列名、列声明与字典值**，不是整段 SQL 文本 —— 后者会让任何注释调整都误报。
 * 五张表的**每一列**都在 {@link #EXPECTED_COLUMNS} 里逐字钉死：这些名字是后续 Task
 * （实体 / Mapper / DTO / {@code RecordSheetService}）的编译契约，丢一列都必须在本地变红。
 */
class V46MigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V46__record_sheets.sql";

    /** 五张新表；建表、列清单、{@code deleted_at} 都按这个集合遍历。 */
    private static final List<String> TABLES = List.of(
            "biz_attachment", "biz_receive_record", "biz_receive_issue",
            "biz_source_info", "biz_disposal_record");

    /**
     * 用 {@code owner_type + owner_id} 多态定位宿主的表。
     *
     * <p>{@code biz_receive_issue} **不在**此列：它靠 {@code receive_id} 挂在某条接收信息下，
     * 自身没有 owner 列（只有宿主才需要多态定位）。
     */
    private static final List<String> OWNER_SCOPED_TABLES = List.of(
            "biz_attachment", "biz_receive_record", "biz_source_info", "biz_disposal_record");

    /**
     * 每张表的**完整列清单**（{@code 列名:声明}），逐字对应设计 §4.2 的 DDL。
     *
     * <p>声明段按空白折叠比对（正则用 {@code \s+} 连接各 token），所以 SQL 的对齐空格
     * 随便调都不会误报；但列名、类型、{@code NOT NULL} / {@code DEFAULT} 必须一致。
     */
    private static final Map<String, List<String>> EXPECTED_COLUMNS = Map.of(
            "biz_attachment", List.of(
                    "id:BIGSERIAL PRIMARY KEY",
                    "owner_type:VARCHAR(30) NOT NULL",
                    "owner_id:BIGINT NOT NULL",
                    "biz_type:VARCHAR(40) NOT NULL",
                    "file_id:BIGINT NOT NULL",
                    "sort:INTEGER NOT NULL DEFAULT 0",
                    "created_at:TIMESTAMPTZ NOT NULL DEFAULT now()",
                    "updated_at:TIMESTAMPTZ",
                    "created_by:BIGINT",
                    "updated_by:BIGINT",
                    "deleted_at:TIMESTAMPTZ"),
            "biz_receive_record", List.of(
                    "id:BIGSERIAL PRIMARY KEY",
                    "owner_type:VARCHAR(20) NOT NULL",
                    "owner_id:BIGINT NOT NULL",
                    "handover_type:VARCHAR(50)",
                    "doc_name:VARCHAR(200)",
                    "handover_user_id:BIGINT",
                    "handover_user_name:VARCHAR(100)",
                    "handover_date:DATE",
                    "remark:VARCHAR(500)",
                    "created_at:TIMESTAMPTZ NOT NULL DEFAULT now()",
                    "updated_at:TIMESTAMPTZ",
                    "created_by:BIGINT",
                    "updated_by:BIGINT",
                    "deleted_at:TIMESTAMPTZ"),
            "biz_receive_issue", List.of(
                    "id:BIGSERIAL PRIMARY KEY",
                    "receive_id:BIGINT NOT NULL",
                    "issue_type:VARCHAR(50)",
                    "description:VARCHAR(1000)",
                    "discoverer_id:BIGINT",
                    "discoverer_name:VARCHAR(100)",
                    "sort:INTEGER NOT NULL DEFAULT 0",
                    "created_at:TIMESTAMPTZ NOT NULL DEFAULT now()",
                    "updated_at:TIMESTAMPTZ",
                    "created_by:BIGINT",
                    "updated_by:BIGINT",
                    "deleted_at:TIMESTAMPTZ"),
            "biz_source_info", List.of(
                    "id:BIGSERIAL PRIMARY KEY",
                    "owner_type:VARCHAR(20) NOT NULL",
                    "owner_id:BIGINT NOT NULL",
                    "source_person_id:BIGINT",
                    "source_person_name:VARCHAR(100)",
                    "source_unit:VARCHAR(200)",
                    "source_date:DATE",
                    "source_desc:VARCHAR(1000)",
                    "created_at:TIMESTAMPTZ NOT NULL DEFAULT now()",
                    "updated_at:TIMESTAMPTZ",
                    "created_by:BIGINT",
                    "updated_by:BIGINT",
                    "deleted_at:TIMESTAMPTZ"),
            "biz_disposal_record", List.of(
                    "id:BIGSERIAL PRIMARY KEY",
                    "owner_type:VARCHAR(20) NOT NULL",
                    "owner_id:BIGINT NOT NULL",
                    "disposal_type:VARCHAR(50)",
                    "disposal_user_id:BIGINT",
                    "disposal_user_name:VARCHAR(100)",
                    "amount_wan:NUMERIC(18,2)",
                    "disposal_date:DATE",
                    "remark:VARCHAR(500)",
                    "created_at:TIMESTAMPTZ NOT NULL DEFAULT now()",
                    "updated_at:TIMESTAMPTZ",
                    "created_by:BIGINT",
                    "updated_by:BIGINT",
                    "deleted_at:TIMESTAMPTZ"));

    /** 软删列必须是**真正的列声明**，不能靠注释里的同名字符串蒙混过关。 */
    private static final Pattern DELETED_AT_COLUMN =
            Pattern.compile("(?m)^\\s*deleted_at\\s+TIMESTAMPTZ\\s*$");

    private static final Pattern OWNER_TYPE_NOT_NULL =
            Pattern.compile("(?m)^\\s*owner_type\\s+VARCHAR\\(\\d+\\)\\s+NOT NULL\\b");

    private static final Pattern OWNER_ID_NOT_NULL =
            Pattern.compile("(?m)^\\s*owner_id\\s+BIGINT\\s+NOT NULL\\b");

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in = V46MigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V46 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 截取某张表的建表主体：从 {@code CREATE TABLE} 到行首的 {@code );}。 */
    private static String tableBody(String table) {
        int start = SQL.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        assertThat(start).as(table + " 必须被创建").isGreaterThanOrEqualTo(0);
        int end = SQL.indexOf("\n);", start);
        assertThat(end).as(table + " 的建表语句必须正常闭合").isGreaterThan(start);
        return SQL.substring(start, end);
    }

    /**
     * {@code 列名 + 声明} → 锚定行首、容忍对齐空格的正则。
     *
     * <p>锚定 {@code ^} 是刻意的：注释里出现同名字符串不算数；声明按 token 折叠空白，
     * 因此 {@code amount_wan         NUMERIC(18,2),} 与 {@code amount_wan NUMERIC(18,2)}
     * 等价，但 {@code NUMERIC(18,2)} 与 {@code NUMERIC(18,20)} 不等价。
     */
    private static Pattern columnPattern(String column, String declaration) {
        String type = Arrays.stream(declaration.trim().split("\\s+"))
                .map(Pattern::quote)
                .collect(Collectors.joining("\\s+"));
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(column) + "\\s+" + type + "(?=\\s|,|;|$)");
    }

    @Test
    @DisplayName("五张新表都以 IF NOT EXISTS 建出，保证迁移可重复执行")
    void createsAllFiveTablesIdempotently() {
        assertThat(SQL).contains(TABLES.stream()
                .map(table -> "CREATE TABLE IF NOT EXISTS " + table)
                .toArray(String[]::new));
        assertThat(EXPECTED_COLUMNS.keySet()).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    @DisplayName("五张表的每一列都按设计 §4.2 声明（列名 + 类型），合并冲突丢一行即红")
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
    @DisplayName("来源明细的唯一索引是部分索引（WHERE deleted_at IS NULL），软删后可以重录")
    void sourceInfoUniqueIndexIsPartial() {
        assertThat(SQL)
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_source_info_owner")
                .contains("ON biz_source_info (owner_type, owner_id) WHERE deleted_at IS NULL");
    }

    @Test
    @DisplayName("每张记录表都有 deleted_at TIMESTAMPTZ 列，否则软删无处落值")
    void everyRecordTableHasDeletedAt() {
        for (String table : TABLES) {
            assertThat(tableBody(table))
                    .as("%s 必须有 deleted_at TIMESTAMPTZ 列", table)
                    .containsPattern(DELETED_AT_COLUMN);
        }
    }

    @Test
    @DisplayName("多态宿主的 owner_type / owner_id 都是 NOT NULL；子表 biz_receive_issue 不带 owner 列")
    void ownerColumnsAreNotNull() {
        assertThat(OWNER_SCOPED_TABLES)
                .as("多态宿主表清单必须与列清单里带 owner_type 的表一致")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_COLUMNS.entrySet().stream()
                        .filter(entry -> entry.getValue().stream().anyMatch(spec -> spec.startsWith("owner_type:")))
                        .map(Map.Entry::getKey)
                        .toList());
        assertThat(EXPECTED_COLUMNS.get("biz_receive_issue"))
                .as("biz_receive_issue 通过 receive_id 挂宿主，不应有 owner_type")
                .noneMatch(spec -> spec.startsWith("owner_type:"));

        for (String table : OWNER_SCOPED_TABLES) {
            String body = tableBody(table);
            assertThat(body).as("%s.owner_type 必须 NOT NULL", table).containsPattern(OWNER_TYPE_NOT_NULL);
            assertThat(body).as("%s.owner_id 必须 NOT NULL", table).containsPattern(OWNER_ID_NOT_NULL);
        }
    }

    @Test
    @DisplayName("disposal_order 扩列：处置人 / 处置日期 / 备注")
    void extendsDisposalOrder() {
        assertThat(SQL)
                .contains("ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_id")
                .contains("ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_user_name")
                .contains("ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS disposal_date")
                .contains("ALTER TABLE disposal_order ADD COLUMN IF NOT EXISTS remark");
    }

    @Test
    @DisplayName("三组字典类型与全部字典项都落种子")
    void seedsThreeDictionaries() {
        assertThat(SQL).contains(
                "('disposal_type', '处置类型'",
                "('handover_type', '交接类型'",
                "('issue_type', '问题类型'");
        assertThat(SQL)
                .contains("('disposal_type', 'sale'")
                .contains("('disposal_type', 'scrap'")
                .contains("('disposal_type', 'transfer'")
                .contains("('handover_type', 'receive'")
                .contains("('handover_type', 'handover'")
                .contains("('handover_type', 'internal'")
                .contains("('issue_type', 'ownership'")
                .contains("('issue_type', 'certificate'")
                .contains("('issue_type', 'facility'")
                .contains("('issue_type', 'arrears'");
    }

    @Test
    @DisplayName("不新增菜单、不触碰 project_zone：本任务的两条硬约束可回归")
    void addsNoMenuRowsAndLeavesProjectZoneUntouched() {
        assertThat(SQL)
                .as("本任务不新增菜单（asset.ledger / asset.project / operation.disposal 均已在 V45 种子中）")
                .doesNotContain("INSERT INTO menu", "sys_role_menu");
        assertThat(SQL)
                .as("project_zone.deleted_at 列已由 V24 建好，本迁移不得再动它")
                .doesNotContain("project_zone");
    }
}
