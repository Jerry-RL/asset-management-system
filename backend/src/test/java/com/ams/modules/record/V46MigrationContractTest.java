package com.ams.modules.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * <p>刻意断言的是**列名与字典值**，不是整段 SQL 文本 —— 后者会让任何注释调整都误报。
 */
class V46MigrationContractTest {

    private static final String SQL = readMigration();

    private static String readMigration() {
        try (InputStream in = V46MigrationContractTest.class
                .getResourceAsStream("/db/migration/V46__record_sheets.sql")) {
            assertThat(in).as("V46 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("五张新表都以 IF NOT EXISTS 建出，保证迁移可重复执行")
    void createsAllFiveTablesIdempotently() {
        assertThat(SQL).contains(
                "CREATE TABLE IF NOT EXISTS biz_attachment",
                "CREATE TABLE IF NOT EXISTS biz_receive_record",
                "CREATE TABLE IF NOT EXISTS biz_receive_issue",
                "CREATE TABLE IF NOT EXISTS biz_source_info",
                "CREATE TABLE IF NOT EXISTS biz_disposal_record");
    }

    @Test
    @DisplayName("来源明细的唯一索引是部分索引（WHERE deleted_at IS NULL），软删后可以重录")
    void sourceInfoUniqueIndexIsPartial() {
        assertThat(SQL)
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_source_info_owner")
                .contains("ON biz_source_info (owner_type, owner_id) WHERE deleted_at IS NULL");
    }

    @Test
    @DisplayName("每张记录表都有 deleted_at，否则软删无处落值")
    void everyRecordTableHasDeletedAt() {
        for (String table : new String[] {
                "biz_attachment", "biz_receive_record", "biz_receive_issue",
                "biz_source_info", "biz_disposal_record"}) {
            int start = SQL.indexOf("CREATE TABLE IF NOT EXISTS " + table);
            assertThat(start).as(table + " 必须被创建").isGreaterThanOrEqualTo(0);
            int end = SQL.indexOf(");", start);
            assertThat(SQL.substring(start, end))
                    .as(table + " 必须有 deleted_at 列")
                    .contains("deleted_at");
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
}
