package com.ams.modules.transferrecord;

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
 * V55 迁移的契约守卫（资产调拨记录）。
 *
 * <p>每条断言对应一类真实事故：
 * <ol>
 *   <li>表名 / 列名与实体 {@code @TableName} 漂移 —— MyBatis-Plus 会去查一张不存在的表；</li>
 *   <li>漏掉「同单同资产唯一」的索引 —— 一张单里同一个资产出现两次，生效时写两遍；</li>
 *   <li>明细表被顺手加上 {@code deleted_at} —— 明细是主单的全量替换（先删后插），
 *       留软删只会制造永不清理的孤儿行；</li>
 *   <li>菜单漏了 view 回填 —— 除超管外所有人看不到入口，新功能表现为「没做出来」；</li>
 *   <li>path 与前端镜像不一致 —— 点菜单落回首页。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 */
class V55AssetTransferRecordMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V55__asset_transfer_record.sql";

    private static final String MAIN_TABLE = "asset_transfer_record";
    private static final String ASSET_TABLE = "asset_transfer_record_asset";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V55AssetTransferRecordMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V55 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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
                .as("字段必须齐全：所属公司 / 前部门 / 新部门 / 新责任人 / 截止时间 / 原因 / 备注 / 状态")
                .contains("company_id", "from_department_id", "to_department_id", "to_user_id",
                        "approval_deadline", "reason", "remark",
                        "effected_at", "deleted_at",
                        "created_at", "updated_at", "created_by", "updated_by");
        assertThat(sql)
                .as("status 默认 draft：本期状态机是 draft → completed，没有 approving")
                .contains("status             VARCHAR(20)  NOT NULL DEFAULT 'draft'");
    }

    @Test
    @DisplayName("明细表包含 原值快照 两列：生效后再调拨 / 部门改名也能追溯从哪交接来的")
    void createsAssetTableWithSnapshotColumns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + ASSET_TABLE);
        assertThat(sql)
                .as("两个原值快照列不能省：靠 asset 现值反推是「读完就变了」的数据")
                .contains("record_id", "asset_id", "from_department_id", "from_user_id");
    }

    @Test
    @DisplayName("明细表的列集恰好是 9 列：附件挂在主单上，明细行无外部引用，故刻意不落 deleted_at")
    void assetTableColumnsAreExact() {
        String sql = sqlWithoutComments(SQL);

        int assetTableStart = sql.indexOf("CREATE TABLE IF NOT EXISTS " + ASSET_TABLE);
        int assetTableEnd = sql.indexOf(";", assetTableStart);
        assertThat(assetTableStart).as("明细表必须存在").isPositive();

        // 用「列集恰好相等」而不是 doesNotContain("deleted_at")：后者只能证明少了一列，
        // 前者同时证明没有多出任何列（含 deleted_at）。留软删只会制造永不清理的孤儿行。
        List<String> columns = sql.substring(assetTableStart, assetTableEnd)
                .lines()
                .skip(1)
                .map(String::trim)
                .filter(line -> line.contains(" "))
                .map(line -> line.split("\\s+")[0])
                .toList();

        assertThat(columns).containsExactly(
                "id", "record_id", "asset_id",
                "from_department_id", "from_user_id",
                "created_at", "updated_at", "created_by", "updated_by");
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
                .contains("CREATE INDEX IF NOT EXISTS idx_asset_transfer_record_status")
                .contains("(status, id DESC)");
        assertThat(sql)
                .as("列表页按所属公司筛选")
                .contains("CREATE INDEX IF NOT EXISTS idx_asset_transfer_record_company");
        assertThat(sql)
                .as("缺了它同一张单里同一个资产会出现两次，生效时写两遍")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_transfer_record_asset")
                .contains("(record_id, asset_id)");
        assertThat(sql)
                .as("反查「某资产被哪些调拨记录改过」——资产档案时间线与排查都依赖它")
                .contains("CREATE INDEX IF NOT EXISTS idx_asset_transfer_record_asset_asset")
                .contains("(asset_id)");
    }

    // ------------------------------------------------------------------
    // 与本模块无关的东西：不能顺手抄过来
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不引入本模块用不到的字段与字典：不接审批、不做金额、不做交接清单")
    void doesNotDragInUnrelatedColumns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("无审批引擎：不留悬空字段与 approving 状态")
                .doesNotContain("approval_instance_id")
                .doesNotContain("reject_reason")
                .doesNotContain("approving");
        assertThat(sql)
                .as("金额与交接清单是权属流转才有的（改公司才需要对账与计价）")
                .doesNotContain("amount_wan")
                .doesNotContain("handover_json");
        assertThat(sql)
                .as("全部下拉取自组织架构（公司 / 部门 / 员工），本模块没有枚举字段，故不建字典")
                .doesNotContain("sys_dict_type")
                .doesNotContain("sys_dict_item");
    }

    // ------------------------------------------------------------------
    // 菜单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("菜单挂在 deed 目录下：path 与前端镜像逐字一致，且只回填 view")
    void seedsMenuAndBackfillsViewOnly() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("path 写错会让点菜单落回首页；前端 PATH_TO_CODE 会是 '/asset-transfer-records'")
                .contains("'deed.transferRecord', '资产调拨记录', 'menu', '/asset-transfer-records'")
                .contains("FROM menu d WHERE d.code = 'deed'")
                .as("sort 46 落在「权属流转」(45) 之后")
                .contains("46, d.id");

        assertThat(sql)
                .as("不回填 view 则除超管外所有角色看不到入口")
                .contains("INSERT INTO role_permission")
                .contains("AND m.code = 'deed.transferRecord'")
                .as("排除 super_admin：它由 PermissionRegistry 特判全通，回填是脏数据")
                .contains("r.code <> 'super_admin'");
        assertThat(sql)
                .as("写动作一律不回填：V45 §5.3 把动作级回填列为上线前置人工步骤")
                .doesNotContain("'create'")
                .doesNotContain("'update'")
                .doesNotContain("'delete'");
        assertThat(sql)
                .as("不复用既有 deed.transfer（资产调拨，单资产改经营公司）的 code，否则会顶掉它的菜单")
                .contains("'deed.transferRecord'")
                .doesNotContain("'deed.transfer'");
    }

    @Test
    @DisplayName("两处幂等兜底都在：重复执行迁移不得报错或产生重复行")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("菜单 ON CONFLICT (code)，权限回填 ON CONFLICT")
                .contains("ON CONFLICT (code) DO NOTHING")
                .contains("ON CONFLICT DO NOTHING");
    }
}
