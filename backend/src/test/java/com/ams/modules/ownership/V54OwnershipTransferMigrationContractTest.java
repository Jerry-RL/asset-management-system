package com.ams.modules.ownership;

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
 * V54 迁移的契约守卫（权属流转）。
 *
 * <p>每条断言对应一类真实事故：
 * <ol>
 *   <li>表名 / 列名与实体 {@code @TableName} 漂移 —— MyBatis-Plus 会去查一张不存在的表；</li>
 *   <li>漏掉「同单同资产唯一」的索引 —— 一张单里同一个资产出现两次，生效时改两遍；</li>
 *   <li>漏掉 {@code asset.ownership_status} —— 外部流转与处置在资产上都表现为「已退出」，
 *       档案里无法区分「卖掉了」和「转出去了」；</li>
 *   <li>{@code handover_json} 误用 JSONB —— 实体字段是 String，insert 时类型不匹配
 *       （V4 已把全仓 JSONB 改回 TEXT，这里不能再踩一次）；</li>
 *   <li>菜单漏了 view 回填 —— 除超管外所有人看不到入口，新功能表现为「没做出来」。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 */
class V54OwnershipTransferMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V54__ownership_transfer.sql";

    private static final String MAIN_TABLE = "ownership_transfer";
    private static final String ASSET_TABLE = "ownership_transfer_asset";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V54OwnershipTransferMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V54 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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
    @DisplayName("主单表包含全部字段，且 status 默认 draft")
    void createsMainTable() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + MAIN_TABLE);
        assertThat(sql)
                .as("字段必须齐全：方向 / 权属类型 / 两个公司 / 流转类型 / 申请人 / 金额 / 交接清单 / 状态")
                .contains("direction", "transfer_scope", "from_company_id", "to_company_id",
                        "transfer_mode", "applicant_user_id", "applicant_name",
                        "approval_deadline", "amount_wan", "reason", "handover_json",
                        "effected_at", "deleted_at",
                        "created_at", "updated_at", "created_by", "updated_by");
        assertThat(sql)
                .as("status 默认 draft：本期状态机是 draft → completed，没有 approving")
                .contains("status            VARCHAR(20)  NOT NULL DEFAULT 'draft'");
    }

    @Test
    @DisplayName("明细表包含 原值快照 两列：生效后公司改名 / 再流转也能追溯从哪家转出")
    void createsAssetTableWithSnapshotColumns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + ASSET_TABLE);
        assertThat(sql)
                .as("两个原值快照列不能省：靠 asset 现值反推是「读完就变了」的数据")
                .contains("transfer_id", "asset_id",
                        "from_property_company_id", "from_operating_company_id");
    }

    @Test
    @DisplayName("明细刻意不落 deleted_at：草稿改明细走全量 diff 真删，明细行无外部引用")
    void assetTableHasNoSoftDelete() {
        String sql = sqlWithoutComments(SQL);

        int assetTableStart = sql.indexOf("CREATE TABLE IF NOT EXISTS " + ASSET_TABLE);
        int assetTableEnd = sql.indexOf(";", assetTableStart);
        assertThat(assetTableStart).as("明细表必须存在").isPositive();
        assertThat(sql.substring(assetTableStart, assetTableEnd))
                .as("附件挂在主单上，明细行无外部引用；留软删只会制造永不清理的孤儿行")
                .doesNotContain("deleted_at");
    }

    // ------------------------------------------------------------------
    // 索引
    // ------------------------------------------------------------------

    @Test
    @DisplayName("四个索引都建出来，且唯一索引约束「同一张单不重复挂同一资产」")
    void createsExpectedIndexes() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("列表页默认按状态筛选 + id 倒序")
                .contains("CREATE INDEX IF NOT EXISTS idx_ownership_transfer_status")
                .contains("(status, id DESC)");
        assertThat(sql)
                .as("列表页按原公司筛选")
                .contains("CREATE INDEX IF NOT EXISTS idx_ownership_transfer_from");
        assertThat(sql)
                .as("缺了它同一张单里同一个资产会出现两次，生效时改两遍")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_ownership_transfer_asset")
                .contains("(transfer_id, asset_id)");
        assertThat(sql)
                .as("反查「某资产被哪些流转单改过」——资产档案时间线与排查都依赖它")
                .contains("CREATE INDEX IF NOT EXISTS idx_ownership_transfer_asset_asset")
                .contains("(asset_id)");
    }

    // ------------------------------------------------------------------
    // asset 加列
    // ------------------------------------------------------------------

    @Test
    @DisplayName("asset.ownership_status 是带默认值的 NOT NULL：存量行一律「集团内」")
    void addsOwnershipStatusColumn() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("没有这一列就无法在资产上区分「已对外转出」与「已处置」")
                .contains("ALTER TABLE asset ADD COLUMN IF NOT EXISTS ownership_status")
                .contains("NOT NULL DEFAULT 'in_group'");
    }

    // ------------------------------------------------------------------
    // 字典 / 菜单 / 通知模板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三个字典类型 + 九条字典项齐全")
    void seedsDictionaries() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains(
                "('transfer_direction', '流转方向', 16)",
                "('transfer_scope',     '权属类型', 17)",
                "('transfer_mode',      '流转类型', 18)");

        assertThat(List.of(
                "('transfer_direction', 'internal',  '内部流转',     1)",
                "('transfer_direction', 'external',  '外部流转',     2)",
                "('transfer_scope',     'both',      '经营权且产权', 1)",
                "('transfer_scope',     'property',  '产权',         2)",
                "('transfer_scope',     'operating', '经营权',       3)",
                "('transfer_mode',      'allocate',  '直接划拨',     1)",
                "('transfer_mode',      'purchase',  '购买流转',     2)",
                "('transfer_mode',      'auction',   '拍卖流转',     3)"))
                .as("字典项少一条，前端下拉就少一个可选项；值必须与设计 §4.5 逐字一致")
                .allSatisfy(row -> assertThat(sql).contains(row));
    }

    @Test
    @DisplayName("菜单挂在 deed 目录下：path 与前端镜像逐字一致，且只回填 view")
    void seedsMenuAndBackfillsViewOnly() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("path 写错会让点菜单落回首页；前端 PATH_TO_CODE 会是 '/ownership-transfers'")
                .contains("'deed.ownershipTransfer', '权属流转', 'menu', '/ownership-transfers'")
                .contains("FROM menu d WHERE d.code = 'deed'")
                .as("sort 45 落在「评估申请」(40) 之后")
                .contains("45, d.id");

        assertThat(sql)
                .as("不回填 view 则除超管外所有角色看不到入口")
                .contains("INSERT INTO role_permission")
                .contains("AND m.code = 'deed.ownershipTransfer'")
                .as("排除 super_admin：它由 PermissionRegistry 特判全通，回填是脏数据")
                .contains("r.code <> 'super_admin'");
        assertThat(sql)
                .as("写动作一律不回填：V45 §5.3 把动作级回填列为上线前置人工步骤")
                .doesNotContain("'create'")
                .doesNotContain("'update'")
                .doesNotContain("'delete'");
    }

    @Test
    @DisplayName("通知模板 ownership_transferred 幂等插入")
    void seedsNotificationTemplate() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .contains("INSERT INTO notification_template")
                .contains("'ownership_transferred'")
                .contains("WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE code = 'ownership_transferred')");
    }

    @Test
    @DisplayName("两处幂等兜底都在：重复执行迁移不得报错或产生重复行")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("字典类型/项 ON CONFLICT，菜单 ON CONFLICT，权限回填 ON CONFLICT")
                .contains("ON CONFLICT (code) DO NOTHING")
                .contains("ON CONFLICT (type_id, value) DO NOTHING")
                .contains("ON CONFLICT DO NOTHING");
    }
}
