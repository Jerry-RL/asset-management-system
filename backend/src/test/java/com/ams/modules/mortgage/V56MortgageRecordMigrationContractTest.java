package com.ams.modules.mortgage;

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
 * V56 迁移的契约守卫（抵押记录：项目 / 分区 / 资产三级标的）。
 *
 * <p>本迁移是**扩展**既有 {@code mortgage} 表，不是新建表 —— 这一点是本类的核心断言。
 * 每条断言对应一类真实事故：
 * <ol>
 *   <li>顺手新建 {@code mortgage_record} 表 —— 「项目被抵押」会对既有的 5 处在押校验、
 *       权证抵押状态、到期预警完全不可见，被抵押项目下的资产照样能被处置和流转；</li>
 *   <li>忘记回填存量行 —— V2 以来的资产抵押记录 {@code target_id} 为 NULL，
 *       升级后在押校验一条都命中不了，表现为「原来的抵押全部失效」；</li>
 *   <li>{@code target_id} 允许 NULL —— 没有标的的抵押记录静默地拦不住任何人；</li>
 *   <li>{@code asset_id} 没放开 NOT NULL —— 项目 / 分区抵押根本插不进去；</li>
 *   <li>合同编号唯一索引不是 partial —— 草稿阶段绝大多数记录还没拿到合同号，
 *       第二条空编号的草稿会被唯一索引拒掉；</li>
 *   <li>菜单改名时顺手改了 path —— 既有书签与前端镜像会失配。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红。
 * 本迁移的注释很长（要解释为什么不新建表），这个处理是必须的。
 */
class V56MortgageRecordMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V56__mortgage_record.sql";

    private static final String TABLE = "mortgage";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V56MortgageRecordMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V56 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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
    // 扩展既有表，而不是新建表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不新建任何表：抵押标的扩到项目 / 分区必须落在既有 mortgage 表上")
    void extendsExistingTableInsteadOfCreatingANewOne() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("新建 mortgage_record 会让「项目被抵押」对 assertNotMortgaged（5 处前置校验）、"
                        + "asset_certificate.mortgage_status、listExpiring 全部不可见 —— "
                        + "那是静默的正确性漏洞，不是「两个模块并存」")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("mortgage_record");
        // 也不能为了让新表参与校验而复制一条规则出来。
        // 注意不能笼统地断言 doesNotContain("EXISTS") —— 本迁移到处都是 IF NOT EXISTS。
        assertThat(sql)
                .as("在 SQL 里复制一份三级在押判定，会与 MortgageMapper.COVERING_CONDITION 漂移")
                .doesNotContain("COVERING")
                .doesNotContain("WHEN EXISTS")
                .doesNotContain("target_type = 'asset'   AND");
    }

    @Test
    @DisplayName("标的列扩齐：target_type / target_id / company_id + 表单字段")
    void addsTargetAndFormColumns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("三级标的必须的两列 + 所属公司")
                .contains("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS target_type")
                .contains("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS target_id")
                .contains("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS company_id");
        assertThat(sql)
                .as("表单字段：利率 / 银行 / 还款日 / 期限（月）/ 合同编号")
                .contains("ADD COLUMN IF NOT EXISTS interest_rate   NUMERIC(8,4)")
                .contains("ADD COLUMN IF NOT EXISTS bank            VARCHAR(200)")
                .contains("ADD COLUMN IF NOT EXISTS repayment_date  DATE")
                .contains("ADD COLUMN IF NOT EXISTS term_months     INTEGER")
                .contains("ADD COLUMN IF NOT EXISTS contract_no     VARCHAR(100)");
        assertThat(sql)
                .as("草稿可删，必须软删")
                .contains("ADD COLUMN IF NOT EXISTS deleted_at      TIMESTAMPTZ");
        assertThat(sql)
                .as("start_date / end_date / status / release_* / file_id 都是 V2、V10 已有的列，"
                        + "重复 ALTER 会在某些 PG 版本上报错，而且会让「谁是权威定义」变得模糊")
                .doesNotContain("ADD COLUMN IF NOT EXISTS start_date")
                .doesNotContain("ADD COLUMN IF NOT EXISTS end_date")
                .doesNotContain("ADD COLUMN IF NOT EXISTS status")
                .doesNotContain("ADD COLUMN IF NOT EXISTS release_status")
                .doesNotContain("ADD COLUMN IF NOT EXISTS amount");
    }

    // ------------------------------------------------------------------
    // 存量行回填与约束
    // ------------------------------------------------------------------

    @Test
    @DisplayName("存量行回填：target_type='asset' + target_id=asset_id + company_id 取资产公司")
    void backfillsExistingRows() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("V2 建表时 asset_id 是 NOT NULL，所以存量行只可能是资产抵押；"
                        + "不回填 target_id 会让升级前的抵押在三级在押校验里全部失效")
                .contains("UPDATE mortgage SET target_type = 'asset' WHERE target_type IS NULL")
                .contains("UPDATE mortgage SET target_id   = asset_id  WHERE target_id   IS NULL");
        assertThat(sql)
                .as("所属公司由资产公司回填：列表页按公司筛选，不回填则存量行筛不出来")
                .contains("SET company_id = a.asset_company_id")
                .contains("FROM asset a")
                .contains("WHERE a.id = m.asset_id");
    }

    @Test
    @DisplayName("约束：target_type 默认 asset 且 NOT NULL，target_id NOT NULL，asset_id 放开")
    void setsTargetConstraints() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("target_type 给默认值：漏写时退化成资产抵押而不是 NULL")
                .contains("ALTER COLUMN target_type SET DEFAULT 'asset'")
                .contains("ALTER COLUMN target_type SET NOT NULL");
        assertThat(sql)
                .as("target_id 必须 NOT NULL：没有标的的抵押记录既拦不住人、也无法排查")
                .contains("ALTER COLUMN target_id   SET NOT NULL");
        assertThat(sql)
                .as("asset_id 必须放开 NOT NULL，否则项目 / 分区抵押插不进去")
                .contains("ALTER COLUMN asset_id DROP NOT NULL");
        assertThat(sql)
                .as("company_id 刻意不设 NOT NULL：asset.asset_company_id 本身可空，"
                        + "NOT NULL 会让迁移在某些数据集上于上线时刻直接失败")
                .doesNotContain("ALTER COLUMN company_id SET NOT NULL");
    }

    // ------------------------------------------------------------------
    // 索引
    // ------------------------------------------------------------------

    @Test
    @DisplayName("四条索引：列表筛选两条 + 在押校验一条 + 合同编号部分唯一索引")
    void createsExpectedIndexes() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("列表页按标的类型筛选")
                .contains("CREATE INDEX IF NOT EXISTS idx_mortgage_target")
                .contains("(target_type, target_id)");
        assertThat(sql)
                .as("列表页按所属公司筛选")
                .contains("CREATE INDEX IF NOT EXISTS idx_mortgage_company")
                .contains("(company_id)");
        assertThat(sql)
                .as("在押校验按 (status, target_type, target_id) 命中；5 处前置校验都在循环里调它")
                .contains("CREATE INDEX IF NOT EXISTS idx_mortgage_status_target")
                .contains("(status, target_type, target_id)");
        assertThat(sql)
                .as("合同编号唯一必须是 partial：草稿阶段绝大多数记录还没有合同号，"
                        + "普通唯一索引在有 '' 默认值时会互撞")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_mortgage_contract_no")
                .contains("WHERE contract_no IS NOT NULL AND deleted_at IS NULL");
    }

    // ------------------------------------------------------------------
    // 与本模块无关的东西
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不引入本模块用不到的字段与字典：不接审批、不做交接清单、不建字典")
    void doesNotDragInUnrelatedColumns() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("解押沿用既有 mortgage_release 审批流，不新增审批字段")
                .doesNotContain("approval_instance_id")
                .doesNotContain("reject_reason")
                .doesNotContain("approving");
        assertThat(sql)
                .as("交接清单是权属流转才有的")
                .doesNotContain("handover_json");
        assertThat(sql)
                .as("项目类型（项目 / 分区 / 资产）是标的种类，不是字典项；"
                        + "全部下拉取自项目 / 分区 / 资产三张表，不需要字典")
                .doesNotContain("sys_dict_type")
                .doesNotContain("sys_dict_item");
        assertThat(sql)
                .as("标的空间是 3 种（project / zone / asset），做成表只会多一次 join")
                .doesNotContain("CREATE TABLE");
    }

    // ------------------------------------------------------------------
    // 菜单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("菜单只改名，不动 code / path / parent：path 变了会让既有书签落回首页")
    void renamesMenuInPlace() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("该页已从只读列表升级为完整模块，名字要跟上")
                .contains("UPDATE menu SET name = '抵押记录' WHERE code = 'deed.mortgage'");
        assertThat(sql)
                .as("不得改 path / parent_id，也不得另插一条菜单（会与 V45 那条重复）")
                .doesNotContain("SET path")
                .doesNotContain("SET parent_id")
                .doesNotContain("INSERT INTO menu");
    }

    @Test
    @DisplayName("幂等：所有 ALTER 都带 IF NOT EXISTS，重复执行不得报错")
    void everyStatementIsIdempotent() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("每一条 ADD COLUMN 都必须带 IF NOT EXISTS")
                .doesNotContain("ADD COLUMN target_type")
                .doesNotContain("ADD COLUMN target_id")
                .doesNotContain("ADD COLUMN company_id");
        assertThat(sql)
                .as("三条 UPDATE 回填都带 IS NULL 条件，重复执行是 no-op")
                .contains("WHERE target_type IS NULL")
                .contains("WHERE target_id   IS NULL")
                .contains("AND m.company_id IS NULL");
        assertThat(sql)
                .as("索引与菜单改名都必须是 IF NOT EXISTS / 有 WHERE 条件")
                .contains("IF NOT EXISTS uk_mortgage_contract_no");
    }
}
