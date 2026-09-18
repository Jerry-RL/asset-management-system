package com.ams.modules.disposal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V57 迁移的契约守卫（资产处置记录）。
 *
 * <p>每条断言对应一类真实事故：
 * <ol>
 *   <li>表名 / 列名与实体 {@code @TableName} 漂移 —— MyBatis-Plus 会去查一张不存在的表；</li>
 *   <li>漏掉「同一资产唯一」的索引 —— 重复级联会产生第二批副作用（并发时亦然）；</li>
 *   <li>漏掉原权属快照列 —— 公司字段被清空后，再也追溯不到「从哪家公司处置出去」；</li>
 *   <li>金额与单位没成对 —— 资产级是元、项目/分区级是万元，混在一列里界面无法正确展示；</li>
 *   <li>菜单漏了 view 回填 —— 除超管外所有人看不到入口，新功能表现为「没做出来」。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 */
class V57AssetDisposalRecordMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V57__asset_disposal_record.sql";

    private static final String TABLE = "asset_disposal_record";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V57AssetDisposalRecordMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V57 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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
    @DisplayName("台账表包含全部字段：处置对象 / 来源 / 权属快照 / 处置信息")
    void createsLedgerTable() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + TABLE);
        assertThat(sql)
                .as("字段必须齐全：处置对象、来源单据、原权属快照、处置信息、处置时间")
                .contains("asset_id", "target_type", "target_id",
                        "source_order_id", "source_record_id",
                        "from_property_company_id", "from_operating_company_id",
                        "disposal_type", "disposal_amount", "amount_unit",
                        "disposal_date", "disposal_user_id", "disposal_user_name",
                        "remark", "disposed_at",
                        "created_at", "updated_at", "created_by", "updated_by");
    }

    @Test
    @DisplayName("台账表的列集恰好 20 列：无软删列（处置不可逆，台账只增不减）")
    void ledgerTableColumnsAreExact() {
        String sql = sqlWithoutComments(SQL);

        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + TABLE);
        assertThat(start).as("台账表必须存在").isPositive();
        int end = sql.indexOf(";", start);

        List<String> columns = sql.substring(start, end)
                .lines()
                .skip(1)
                .map(String::trim)
                .filter(line -> line.contains(" "))
                .map(line -> line.split("\\s+")[0])
                .toList();

        // 用「列集恰好相等」而不是 doesNotContain("deleted_at")：后者只能证明少了一列，
        // 前者同时证明没有多出任何列（含被误加的软删列）。
        assertThat(columns).containsExactly(
                "id", "asset_id", "target_type", "target_id",
                "source_order_id", "source_record_id",
                "from_property_company_id", "from_operating_company_id",
                "disposal_type", "disposal_amount", "amount_unit",
                "disposal_date", "disposal_user_id", "disposal_user_name",
                "remark", "disposed_at",
                "created_at", "updated_at", "created_by", "updated_by");
    }

    // ------------------------------------------------------------------
    // 索引
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三个索引都建出来，唯一索引约束「一个资产只被处置一次」")
    void createsExpectedIndexes() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("缺了它重复级联会写出第二批台账行，且并发没有兜底")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_disposal_record_asset")
                .contains("(asset_id)");
        assertThat(sql)
                .as("列表页按原产权公司筛选 + id 倒序")
                .contains("CREATE INDEX IF NOT EXISTS idx_asset_disposal_record_company")
                .contains("(from_property_company_id, id DESC)");
        assertThat(sql)
                .as("反查「某项目 / 分区 / 资产的处置带出了哪些资产」")
                .contains("CREATE INDEX IF NOT EXISTS idx_asset_disposal_record_target")
                .contains("(target_type, target_id)");
    }

    // ------------------------------------------------------------------
    // asset 列注释
    // ------------------------------------------------------------------

    @Test
    @DisplayName("asset.ownership_status 的注释写明第三个取值 disposed —— 与 transferred_out 区分")
    void documentsOwnershipStatusValue() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("列是 VARCHAR 无 CHECK，取值只能靠注释约束；漏写 disposed 会让「卖掉了」与「转出去了」在库里无法区分")
                .contains("COMMENT ON COLUMN asset.ownership_status")
                .contains("disposed");
    }

    // ------------------------------------------------------------------
    // 菜单 / 权限回填
    // ------------------------------------------------------------------

    @Test
    @DisplayName("菜单先挂在 operation 目录下（V58 随后把它移入 deed 目录并改名，见 V58 契约测试）")
    void seedsMenuAndBackfillsViewOnly() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("path 写错会让点菜单落回首页；前端 PATH_TO_CODE 会是 '/disposal-records'")
                .contains("'operation.disposalRecord', '资产处置记录', 'menu', '/disposal-records'")
                .contains("FROM menu d WHERE d.code = 'operation'")
                .as("sort 15 落在「资产处置」(10) 之后、「临时占用」(20) 之前")
                .contains("15, d.id");

        assertThat(sql)
                .as("不回填 view 则除超管外所有角色看不到入口")
                .contains("INSERT INTO role_permission")
                .contains("AND m.code = 'operation.disposalRecord'")
                .as("排除 super_admin：它由 PermissionRegistry 特判全通，回填是脏数据")
                .contains("r.code <> 'super_admin'");
        assertThat(sql)
                .as("写动作一律不回填：V45 §5.3 把动作级回填列为上线前置人工步骤")
                .doesNotContain("'create'")
                .doesNotContain("'update'")
                .doesNotContain("'delete'");
    }

    @Test
    @DisplayName("存量回填：已完成的资产级处置单补台账并同步资产状态；不回填项目 / 分区台账")
    void backfillsCompletedAssetDisposals() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("不回填则「资产处置记录」对存量数据是空的，且已处置资产仍挂在原产权公司名下")
                .contains("INSERT INTO asset_disposal_record")
                .contains("FROM disposal_order o")
                .contains("o.status = 'completed'")
                .contains("ON CONFLICT (asset_id) DO NOTHING");
        assertThat(sql)
                .as("回填必须同步资产状态，否则台账与资产字段不一致")
                .contains("SET ownership_status")
                .contains("property_company_id = NULL");
        assertThat(sql)
                .as("刻意不回填项目 / 分区台账（旧语义下是纯登记）：按台账行反向处置整个项目"
                        + "会产生一批未经复核的批量「脱离产权公司」，且不可逆")
                .doesNotContain("FROM biz_disposal_record");
    }

    @Test
    @DisplayName("两处幂等兜底都在：重复执行迁移不得报错或产生重复行")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("建表 IF NOT EXISTS、索引 IF NOT EXISTS、菜单 / 权限 ON CONFLICT DO NOTHING")
                .contains("CREATE TABLE IF NOT EXISTS " + TABLE)
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS")
                .contains("ON CONFLICT (code) DO NOTHING")
                .contains("ON CONFLICT DO NOTHING");
    }
}
