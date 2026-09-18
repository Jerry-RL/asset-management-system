package com.ams.modules.lease;

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
 * V59 迁移的契约守卫（招租发布审批 + 资产租赁管理）。
 *
 * <p>每条断言对应一类真实事故：
 *
 * <ol>
 *   <li>漏了表单列（封面图 / 详情列表图 / 年租金 / 推荐 / 排序 / 介绍）—— 界面上填了存不下去，
 *       或存了读不回来；</li>
 *   <li>多出「顺手加的」列 —— 列集恰好相等才能同时证明「没少列」与「没多列」；</li>
 *   <li>`status` 只改注释不加 CHECK —— 加了约束会在「历史值 + 新值」并存期把回滚路径堵死
 *       （与 V57 的 `ownership_status` 同口径）；</li>
 *   <li>漏了 `approval_flow_def` 的流程定义 —— `ApprovalEngine.start` 会走「无定义 = 自动通过」
 *       分支，招租变成**免审批直接发布**，而需求明确要求「出现一条发布审批数据」；</li>
 *   <li>菜单 path / 父目录 / sort 漂移，或漏了 view 回填 —— 除超管外所有人看不到入口，
 *       新功能表现为「没做出来」。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 */
class V59LeaseListingPublishMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V59__lease_listing_publish.sql";

    private static final String TABLE = "lease_listing";

    /** 本迁移必须新增的列，顺序与 ALTER TABLE 中一致。 */
    private static final List<String> ADDED_COLUMNS = List.of(
            "rent_type", "annual_rent",
            "cover_image_file_id", "cover_image_url", "detail_images",
            "recommended", "sort_no", "intro",
            "reject_reason", "created_by");

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V59LeaseListingPublishMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V59 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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

    /** 迁移里 `ADD COLUMN IF NOT EXISTS <name>` 声明出来的列，按出现顺序。 */
    private static List<String> addedColumns(String sql) {
        Matcher matcher = Pattern
                .compile("ADD COLUMN IF NOT EXISTS\\s+(\\w+)")
                .matcher(sql);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("扩表列集恰好 10 列：表单字段齐全且没有多出任何列")
    void addsExactlyThePublishFormColumns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("必须扩的是既有招租表：另起一张表会让 V40 / V42 的租控派生视图看不到新发布的招租，"
                        + "表现为「审批通过了但资产还是空置」")
                .contains("ALTER TABLE " + TABLE);

        assertThat(addedColumns(sql))
                .as("列集用「恰好相等」而不是 contains：后者只能证明没少列，证明不了没多列")
                .containsExactlyElementsOf(ADDED_COLUMNS);
    }

    @Test
    @DisplayName("status 由二值扩为四值：只改注释，不加 CHECK 约束")
    void documentsFourStatusValuesWithoutCheckConstraint() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("四个取值都要写进注释，否则库表层面看不出 pending / rejected 的语义")
                .contains("COMMENT ON COLUMN " + TABLE + ".status")
                .contains("pending")
                .contains("active")
                .contains("rejected")
                .contains("closed");

        assertThat(sql)
                .as("加 CHECK 会在「历史值 + 新值」并存期把回滚路径堵死（与 V57 ownership_status 同口径）")
                .doesNotContain("CHECK (")
                .doesNotContain("ADD CONSTRAINT");
    }

    @Test
    @DisplayName("两个索引：端上列表排序 + 按资产反查招租记录")
    void createsExpectedIndexes() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("端上固定按 status=active + 推荐优先 + 排序号 + 倒序查询，列顺序必须与查询一致才走索引")
                .contains("CREATE INDEX IF NOT EXISTS idx_lease_listing_pub_sort")
                .contains("(status, recommended DESC, sort_no ASC, id DESC)");
        assertThat(sql)
                .as("「招租记录」抽屉按 assetId 反查")
                .contains("CREATE INDEX IF NOT EXISTS idx_lease_listing_asset_status")
                .contains("(asset_id, status)");
    }

    @Test
    @DisplayName("审批流程定义必须落库：缺它会走「无定义 = 自动通过」，招租变成免审批发布")
    void seedsApprovalFlowDefinition() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("bizType 必须与 LeaseListingService 的 BIZ_TYPE 常量、前端 BIZ_TYPE 标签一致")
                .contains("INSERT INTO approval_flow_def")
                .contains("'lease_listing', '招租发布审批'")
                .contains("\"bizType\":\"lease_listing\"")
                .contains("WHERE NOT EXISTS (SELECT 1 FROM approval_flow_def WHERE biz_type = 'lease_listing')");
    }

    @Test
    @DisplayName("菜单挂在 operation 目录、sort 25、view 回填除超管外全部角色，且不回填写动作")
    void seedsMenuAndBackfillsViewOnly() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("path 写错会让点菜单落回首页；前端 PATH_TO_CODE 是 '/asset-leasing'")
                .contains("'operation.assetLeasing', '资产租赁管理', 'menu', '/asset-leasing'")
                .contains("FROM menu d WHERE d.code = 'operation'")
                .as("sort 25 落在「临时占用」(20) 之后、「资产自用」(30) 之前")
                .contains("25, d.id")
                .as("parent_id 用子查询写 NULL 会让菜单变成没有上级的一级目录，侧边栏直接跳过它")
                .doesNotContain("parent_id = (SELECT");

        assertThat(sql)
                .as("不回填 view 则除超管外所有角色看不到入口")
                .contains("INSERT INTO role_permission")
                .contains("AND m.code = 'operation.assetLeasing'")
                .as("排除 super_admin：它由 PermissionRegistry 特判全通，回填是脏数据")
                .contains("r.code <> 'super_admin'");
        assertThat(sql)
                .as("写动作一律不回填：动作级授权是上线前置的人工步骤")
                .doesNotContain("'create'")
                .doesNotContain("'update'")
                .doesNotContain("'delete'");
    }

    @Test
    @DisplayName("幂等：重复执行迁移不得报错或产生重复行")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("扩列 / 建索引 IF NOT EXISTS、流程定义 WHERE NOT EXISTS、菜单与权限 ON CONFLICT DO NOTHING")
                .contains("ADD COLUMN IF NOT EXISTS")
                .contains("CREATE INDEX IF NOT EXISTS")
                .contains("ON CONFLICT (code) DO NOTHING")
                .contains("ON CONFLICT DO NOTHING");
    }
}
