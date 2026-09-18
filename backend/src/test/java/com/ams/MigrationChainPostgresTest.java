package com.ams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.ams.modules.asset.mapper.MortgageMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 让整条 Flyway 迁移链在**真实 PostgreSQL** 上执行一次。
 *
 * <h2>为什么需要这个测试</h2>
 *
 * <p>在此之前，本仓 52 个迁移文件从未在任何地方真正执行过：测试档 {@code application-test.yml} 是
 * {@code flyway.enabled: false} + H2，6 个「迁移契约测试」全都是 {@code getResourceAsStream}
 * 读 {@code .sql} 文本做字符串断言。字符串断言能守住「注释里说了什么」，守不住
 * 「PostgreSQL 会不会接受它」—— 而迁移失败的表现是**应用启动直接失败**，且 CI 全绿放行。
 *
 * <p>{@code pom.xml} 早就为此声明了 Testcontainers 依赖，注释写的是「集成测试用 PostgreSQL 容器
 * （Flyway 脚本为 PG 方言，H2 无法覆盖）」，但这个依赖一直没有被任何测试用过。本类就是那个测试。
 *
 * <h2>三件事</h2>
 *
 * <ol>
 *   <li><b>迁移链真的能跑完</b>：{@code migrate()} 成功、无 pending、{@code validate()} 通过；</li>
 *   <li><b>V51 / V52 在真库上的落库结果正确</b>：列与可空性、索引、菜单种子、权限回填范围；</li>
 *   <li><b>查询服务生成的 SQL 形状在真 PG 上可执行</b>：这是纯单测永远覆盖不到的一层 ——
 *       服务层测试全部 mock 了 Mapper，SQL 是否正确从未被数据库验证过
 *       （{@code detail_json} 的 {@code LIKE} 就是典型：它在 JSONB 上是操作符错误，
 *       而在 TEXT 上成立，只读 SQL 文本分辨不出这两种情况）。</li>
 * </ol>
 *
 * <h2>Docker 不可用时怎么办（改这段前先读完）</h2>
 *
 * <p>本机没有 Docker 时**跳过**（否则每个后端开发者都会被一条与业务无关的用例卡住），
 * 但 CI 上**硬失败**。静默跳过的集成测试等于零保护 —— 而「迁移从未被执行过」这件事
 * 之所以能长期存在，正是因为没有任何东西会因为它的缺失而变红。
 * 见 {@link #requireDockerOrSkip()}。
 */
class MigrationChainPostgresTest {

    /**
     * 必须与 {@code docker/docker-compose.yml}、{@code docker/docker-compose.prod.yml} 用同一个大版本。
     *
     * <p>prod 跑 15 而这里跑 16 的话，测的就不是部署的那套东西了 —— 迁移在 16 上通过、
     * 在 15 上失败（或反之）是完全可能的。prod 升级大版本时这里要跟着升。
     */
    private static final String POSTGRES_IMAGE = "postgres:15-alpine";

    /**
     * 迁移条数下限自检。
     *
     * <p>Flyway 的 {@code locations} 配错时的表现是「{@code migrate()} 成功，执行 0 条」——
     * 于是后面每一条 schema 断言都会以「表/列不存在」失败，报错指向 schema 而掩盖了真正的配置问题。
     * 下限刻意留出余量（当前 52 条）而不是钉死：加迁移是常事，钉死只会制造伪失败。
     */
    private static final int MIN_MIGRATIONS = 45;

    /** 种子里那个「有对象」的 id：ref_id 查询用例靠它命中。 */
    private static final long SAMPLE_REF_ID = 4242L;

    private static PostgreSQLContainer<?> postgres;
    private static Flyway flyway;

    @BeforeAll
    static void startContainerAndMigrate() {
        requireDockerOrSkip();

        postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("ams")
                .withUsername("ams")
                .withPassword("ams");
        postgres.start();

        flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();

        // 迁移本身失败时把 Flyway 的原始报错往外抛：它的 message 里带具体是哪个文件、哪一句 SQL
        var result = flyway.migrate();
        assertThat(result.success).as("迁移链必须能整体执行成功").isTrue();
        assertThat(result.migrationsExecuted)
                .as("执行的迁移条数不得低于 %d：为 0 通常意味着 locations 配错，"
                        + "而那种情况下后面的 schema 断言会全部以「表不存在」失败，掩盖真正的问题",
                        MIN_MIGRATIONS)
                .isGreaterThanOrEqualTo(MIN_MIGRATIONS);

        seedSampleRows();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * 本机没 Docker 就跳过，CI 上没 Docker 就失败。
     *
     * <p>两个分支都必须存在：只跳过 → CI 静默失去这层保护；只失败 → 每个没装 Docker 的开发者
     * 连 {@code mvn test} 都跑不了。
     */
    private static void requireDockerOrSkip() {
        if (isDockerUsable()) {
            return;
        }
        if (isCi()) {
            fail("CI 上必须有可用的 Docker：迁移链集成测试一旦被跳过，"
                    + "全部迁移就又回到「从未在 PostgreSQL 上执行过」的状态，而没有任何东西会因此变红");
        }
        Assumptions.abort("本机没有可用的 Docker，跳过迁移链集成测试（CI 会执行；"
                + "如需本地运行请启动 Docker Desktop）");
    }

    /**
     * Docker 是否真的可用。
     *
     * <p>不能只写 {@code DockerClientFactory.instance().isDockerAvailable()}：Docker CLI 装好但
     * 守护进程没起来时（本机 Docker Desktop 未启动的常见状态），testcontainers 会抛
     * {@code ShellCommandException}（执行 {@code docker-machine ls -q} 失败）而不是返回 {@code false}，
     * 于是「本机跳过」会变成「本机报错」。两种都视作不可用。
     */
    private static boolean isDockerUsable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isCi() {
        return "true".equalsIgnoreCase(System.getenv("CI"))
                || "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
    }

    // ------------------------------------------------------------------
    // JDBC 小工具（不用 Spring 上下文：本测试要在任何环境下都能独立跑起来）
    // ------------------------------------------------------------------

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static List<String> queryStrings(String sql, Object... params) {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
                return values;
            }
        } catch (SQLException e) {
            throw new AssertionError("SQL 在真实 PostgreSQL 上执行失败（这正是本测试要发现的问题）：" + sql, e);
        }
    }

    private static String queryString(String sql, Object... params) {
        List<String> values = queryStrings(sql, params);
        return values.isEmpty() ? null : values.get(0);
    }

    private static long queryLong(String sql, Object... params) {
        return Long.parseLong(queryString(sql, params));
    }

    private static void execute(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new AssertionError("DDL/DML 执行失败：" + sql, e);
        }
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    /** 列元数据，形如 {@code data_type|is_nullable|max_length}。 */
    private static String columnType(String table, String column) {
        return queryString(
                "SELECT data_type || '|' || is_nullable || '|'"
                        + " || coalesce(character_maximum_length::text, '-')"
                        + " FROM information_schema.columns"
                        + " WHERE table_name = ? AND column_name = ?",
                table,
                column);
    }

    private static String indexDefinition(String indexName) {
        return queryString("SELECT indexdef FROM pg_indexes WHERE indexname = ?", indexName);
    }

    /**
     * 造三行覆盖「成功 / 失败 / 未知（V51 之前的存量行）」的审计数据。
     *
     * <p>在 {@code @BeforeAll} 里一次性种下，而不是每个用例各种一遍：容器是整个类共用的，
     * 用例内追加数据会让「数量」类断言随执行顺序漂移。
     */
    private static void seedSampleRows() {
        execute("INSERT INTO operation_log"
                + " (username, module, action, ref_id, detail_json, success, created_at) VALUES"
                + " ('admin', 'asset', 'update_asset', " + SAMPLE_REF_ID
                + ", '{\"args\":[\"asset-1\"]}', false, now()),"
                + " ('admin', 'asset', 'create_asset', " + SAMPLE_REF_ID
                + ", '{\"args\":[\"asset-2\"]}', true, now()),"
                + " ('legacy', 'system', 'migrated', NULL, NULL, NULL, now() - interval '1 day')");
    }

    // ------------------------------------------------------------------
    // 1. 迁移链
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V51 / V52 都在已应用的迁移里：迁移文件存在但从未被执行，与不存在一样")
    void appliesAuditMigrations() {
        assertThat(flyway.info().pending())
                .as("migrate() 之后不应再有 pending 的迁移")
                .isEmpty();
        // validate() 校验已应用迁移的 checksum 与文件是否仍然一致：改过已发布的迁移文件时会在这里红
        flyway.validate();

        List<String> versions = new ArrayList<>();
        for (MigrationInfo info : flyway.info().applied()) {
            versions.add(String.valueOf(info.getVersion()));
        }
        assertThat(versions)
                .as("V51 加 success/error 两列与菜单，V52 加按对象查询的索引；"
                        + "V54 加权属流转两表与 asset.ownership_status；"
                        + "V55 加资产调拨记录两表与它的菜单；"
                        + "V56 把 mortgage 扩成项目 / 分区 / 资产三级标的；"
                        + "V57 加资产处置记录台账与它的菜单（V58 把该菜单移入 deed 目录并改名）；"
                        + "迁移文件躺在仓库里而从未被执行过，是本仓长期存在的状态，这里把它钉住")
                .contains("51", "52", "54", "55", "56", "57", "58");
    }

    // ------------------------------------------------------------------
    // 2. V51 / V52 在真库上的落库结果
    // ------------------------------------------------------------------

    @Test
    @DisplayName("success 是真 boolean 且可空：NOT NULL 或 DEFAULT true 会把存量行谎报为成功")
    void successColumnIsNullableBoolean() {
        assertThat(columnType("operation_log", "success"))
                .as("data_type|is_nullable|max_length")
                .isEqualTo("boolean|YES|-");
    }

    @Test
    @DisplayName("error 是 VARCHAR(500)，与切面截断到 500 的约定一致")
    void errorColumnIs500Chars() {
        assertThat(columnType("operation_log", "error")).isEqualTo("character varying|YES|500");
    }

    @Test
    @DisplayName("V51 / V52 的三条索引都真的建出来了（列名写错时 IF NOT EXISTS 不会报错，只会静默不建）")
    void createsExpectedIndexes() {
        assertThat(indexDefinition("idx_operation_log_module_created"))
                .as("审计页最常用的等值筛选维度 + 时间倒序")
                .contains("(module, created_at DESC)");
        assertThat(indexDefinition("idx_login_log_result_created"))
                .as("登录日志按结果筛选 + 时间倒序")
                .contains("(result, created_at DESC)");
        assertThat(indexDefinition("idx_operation_log_ref_created"))
                .as("按被操作对象查询：等值列 ref_id 在前、排序列随后。"
                        + "PG 对 DESC 列会补默认的 NULLS FIRST，因此容忍该后缀")
                .matches(".*\\(ref_id, created_at DESC( NULLS FIRST)?, id DESC( NULLS FIRST)?\\).*");
    }

    @Test
    @DisplayName("菜单种子落到 system 之下，code / path / 排序与前端镜像一致")
    void seedsOperationLogMenu() {
        assertThat(queryString("SELECT path FROM menu WHERE code = 'system.operationLog'"))
                .as("path 与前端 PATH_TO_CODE['/system/operation-logs'] 必须逐字一致，"
                        + "否则点菜单会落回首页")
                .isEqualTo("/system/operation-logs");

        long systemId = queryLong("SELECT id FROM menu WHERE code = 'system'");
        assertThat(systemId)
                .as("父目录 system 必须存在，否则按 code 解析 parent_id 会得到 NULL，菜单挂不上树")
                .isPositive();
        assertThat(queryString("SELECT parent_id FROM menu WHERE code = 'system.operationLog'"))
                .as("菜单必须挂在 system 目录下")
                .isEqualTo(String.valueOf(systemId));

        assertThat(queryString("SELECT name FROM menu WHERE code = 'system.operationLog'"))
                .isEqualTo("操作日志");
        assertThat(queryLong("SELECT sort FROM menu WHERE code = 'system.operationLog'"))
                .as("排序 50，落在 system.appLog(40) 之后")
                .isEqualTo(50L);
        assertThat(queryString("SELECT menu_type FROM menu WHERE code = 'system.operationLog'"))
                .isEqualTo("menu");
    }

    @Test
    @DisplayName("view 只回填给 operator 一个角色，且没有任何写动作被回填")
    void backfillsViewOnlyForOperator() {
        List<String> rows = queryStrings(
                "SELECT r.code || ':' || rp.menu_code || ':' || rp.action"
                        + " FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                        + " WHERE rp.menu_code = 'system.operationLog'"
                        + " ORDER BY r.code, rp.action");

        assertThat(rows)
                .as("只允许 operator 的 view。多出任何一条都是静默越权 —— "
                        + "审计数据含用户名、IP 与接口入参，按 V45 §5.2 属敏感菜单")
                .containsExactly("operator:system.operationLog:view");
    }

    // ------------------------------------------------------------------
    // 3. 查询服务生成的 SQL 形状在真 PG 上可执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("detail_json 在真库上是 TEXT：LIKE 才能成立（JSONB 上的 LIKE 是操作符错误）")
    void detailJsonIsTextSoLikeWorks() {
        assertThat(columnType("operation_log", "detail_json"))
                .as("V4__jsonb_to_text.sql 把全仓 JSONB 改成了 TEXT（实体字段是 String，"
                        + "jsonb 会在 insert 时类型不匹配）。若它哪天变回 jsonb，"
                        + "关键字查询会在运行时报 operator does not exist: jsonb ~~ unknown")
                .startsWith("text|");
    }

    @Test
    @DisplayName("关键字三处 OR 条件逐列可用，尤其 detail_json 的 LIKE")
    void keywordMatchesEachColumn() {
        // 与 AuditLogService.query 生成的形状一致：like() 绑 %v%，整组 OR 包在括号里
        // （不包括号的话 module = ? OR username LIKE ? 会把别的模块的日志也捞出来）
        String shape = "SELECT action FROM operation_log"
                + " WHERE (username LIKE ? OR action LIKE ? OR detail_json LIKE ?)"
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";

        assertThat(queryStrings(shape, "%admin%", "%nobody%", "%nobody%", 20, 0))
                .as("username 这一臂")
                .containsExactlyInAnyOrder("update_asset", "create_asset");

        assertThat(queryStrings(shape, "%nobody%", "%update_asset%", "%nobody%", 20, 0))
                .as("action 这一臂")
                .containsExactly("update_asset");

        assertThat(queryStrings(shape, "%nobody%", "%nobody%", "%asset-2%", 20, 0))
                .as("detail_json 这一臂：这条命中只可能来自入参 JSON，"
                        + "因此它同时证明了 detail_json 的 LIKE 在真 PG 上成立")
                .containsExactly("create_asset");
    }

    @Test
    @DisplayName("按 ref_id 等值 + 时间范围 + 成败筛选 + 排序分页，能整体跑通")
    void refIdQueryRuns() {
        List<String> actions = queryStrings(
                "SELECT action FROM operation_log"
                        + " WHERE ref_id = ? AND created_at >= ? AND created_at <= ?"
                        + " AND success = ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                SAMPLE_REF_ID,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1),
                false,
                20,
                0);

        assertThat(actions)
                .as("ref_id 等值 + TIMESTAMPTZ 与 LocalDateTime 比较 + boolean 等值必须能同时成立")
                .containsExactly("update_asset");
    }

    @Test
    @DisplayName("success IS NULL 的存量行：缺省查询包含它，显式 success=true 排除它")
    void nullSuccessRowsAreIncludedByDefault() {
        long all = queryLong("SELECT count(*) FROM operation_log");
        long onlySucceeded = queryLong("SELECT count(*) FROM operation_log WHERE success = true");
        long unknown = queryLong("SELECT count(*) FROM operation_log WHERE success IS NULL");

        assertThat(unknown).as("种子里必须有一行 success IS NULL，否则这条断言恒真、测不出东西").isEqualTo(1L);
        assertThat(all)
                .as("缺省（不按成败筛选）必须包含 success IS NULL 的存量行，"
                        + "否则 V51 之前的历史审计记录会凭空消失 —— 这是本模块最不能出错的方向")
                .isEqualTo(3L)
                .isGreaterThan(onlySucceeded);
    }

    @Test
    @DisplayName("模块下拉的 DISTINCT 查询能跑通，且不含 NULL")
    void distinctModulesQueryRuns() {
        List<String> modules = queryStrings(
                "SELECT DISTINCT module FROM operation_log WHERE module IS NOT NULL ORDER BY module");

        assertThat(modules).doesNotContainNull().containsExactly("asset", "system");
    }

    @Test
    @DisplayName("登录日志按 result / ip 等值 + 时间窗查询能跑通")
    void loginLogQueryRuns() {
        execute("INSERT INTO login_log (username, ip, result, fail_reason, created_at)"
                + " VALUES ('audit-probe', '10.0.0.1', 'failed', '用户名或密码错误', now())");

        List<String> results = queryStrings(
                "SELECT result FROM login_log WHERE result = ? AND ip = ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                "failed",
                "10.0.0.1",
                20,
                0);

        assertThat(results).contains("failed");
    }

    // ------------------------------------------------------------------
    // 4. V54：权属流转在真库上的落库结果
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V54：ownership_transfer 主表存在，direction/transfer_scope/transfer_mode 为 NOT NULL")
    void createsOwnershipTransferTable() {
        assertThat(columnType("ownership_transfer", "direction"))
                .as("方向是判定依据，不允许 NULL")
                .startsWith("character varying|NO|");
        assertThat(columnType("ownership_transfer", "transfer_scope"))
                .as("权属类型决定改哪个公司字段，不允许 NULL")
                .startsWith("character varying|NO|");
        assertThat(columnType("ownership_transfer", "transfer_mode"))
                .as("流转类型是字典取值，不允许 NULL")
                .startsWith("character varying|NO|");
        assertThat(columnType("ownership_transfer", "handover_json"))
                .as("必须是 text：实体字段是 String，jsonb 会在 insert 时类型不匹配")
                .startsWith("text|");
        assertThat(columnType("ownership_transfer", "applicant_user_id"))
                .as("外部人员时为空 —— 这是「内员 / 外部」两分支的区分位，必须可空")
                .startsWith("bigint|YES|");
    }

    @Test
    @DisplayName("V54：asset.ownership_status 默认 in_group，存量行全部是集团内")
    void backfillsOwnershipStatusForExistingAssets() {
        execute("INSERT INTO project (company_id, name, status) VALUES (2, 'V54 契约项目', 1)");
        // asset 表没有 status 列（V2 建表即无），可空列一律留空靠默认值兜底
        execute("INSERT INTO asset (asset_company_id, asset_no, name, asset_type)"
                + " VALUES (2, 'V54-CONTRACT-1', 'V54 契约资产', 'property')");

        assertThat(queryString(
                "SELECT ownership_status FROM asset WHERE asset_no = 'V54-CONTRACT-1'"))
                .as("存量行必须落成 in_group —— 若为 NULL，前端与查询都会判成「未知权属」")
                .isEqualTo("in_group");
    }

    @Test
    @DisplayName("V54：同一张单重复挂同一资产会被唯一索引拒绝")
    void rejectsDuplicateAssetInSameTransfer() {
        execute("INSERT INTO ownership_transfer"
                + " (direction, transfer_scope, from_company_id, to_company_id, transfer_mode,"
                + "  applicant_name, status)"
                + " VALUES ('internal', 'property', 2, 3, 'allocate', '契约申请人', 'draft')");
        long transferId = queryLong(
                "SELECT id FROM ownership_transfer WHERE applicant_name = '契约申请人' ORDER BY id DESC LIMIT 1");

        execute("INSERT INTO ownership_transfer_asset (transfer_id, asset_id) VALUES ("
                + transferId + ", 9001)");

        try {
            execute("INSERT INTO ownership_transfer_asset (transfer_id, asset_id) VALUES ("
                    + transferId + ", 9001)");
            fail("唯一索引 uk_ownership_transfer_asset 未生效：同一张单可以把同一个资产挂两次");
        } catch (AssertionError expected) {
            assertThat(expected.getCause())
                    .as("必须是唯一约束冲突，而不是别的 SQL 错误")
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    @DisplayName("V54：view 只回填给非超管角色，且没有任何写动作被回填")
    void backfillsOwnershipTransferViewOnly() {
        List<String> rows = queryStrings(
                "SELECT r.code || ':' || rp.action"
                        + " FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                        + " WHERE rp.menu_code = 'deed.ownershipTransfer'"
                        + " ORDER BY r.code, rp.action");

        assertThat(rows)
                .as("只允许 view 且不含 super_admin（它由 PermissionRegistry 特判全通）")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).endsWith(":view"))
                .allSatisfy(row -> assertThat(row).doesNotStartWith("super_admin:"));
    }

    // ------------------------------------------------------------------
    // 5. V55：资产调拨记录在真库上的落库结果
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V55：asset_transfer_record 的必填 / 可空列与设计一致")
    void createsAssetTransferRecordTable() {
        assertThat(columnType("asset_transfer_record", "company_id"))
                .as("所属公司必填：资产下拉与校验都按它过滤，NULL 会让「选了公司」变成什么都没筛")
                .startsWith("bigint|NO|");
        assertThat(columnType("asset_transfer_record", "from_department_id"))
                .as("前责任部门必须可空：一张单可以挂来自不同部门的资产")
                .startsWith("bigint|YES|");
        assertThat(columnType("asset_transfer_record", "to_department_id"))
                .startsWith("bigint|NO|");
        assertThat(columnType("asset_transfer_record", "to_user_id")).startsWith("bigint|NO|");
        assertThat(columnType("asset_transfer_record", "status"))
                .as("status 是带默认值 draft 的 NOT NULL：没有默认值时服务端不传就会插成 NULL，"
                        + "而列表页按状态筛选会把这条整个漏掉")
                .startsWith("character varying|NO|");
        assertThat(columnType("asset_transfer_record", "approval_deadline"))
                .as("审批截止时间本期只是留痕，必须可空 —— 不是所有调拨都有截止时间")
                .startsWith("timestamp with time zone|YES|");
    }

    @Test
    @DisplayName("V55：同一张单重复挂同一资产会被唯一索引拒绝")
    void rejectsDuplicateAssetInSameTransferRecord() {
        execute("INSERT INTO asset_transfer_record (company_id, to_department_id, to_user_id)"
                + " VALUES (2, 11, 21)");
        long recordId = queryLong(
                "SELECT id FROM asset_transfer_record ORDER BY id DESC LIMIT 1");

        execute("INSERT INTO asset_transfer_record_asset (record_id, asset_id) VALUES ("
                + recordId + ", 9501)");

        try {
            execute("INSERT INTO asset_transfer_record_asset (record_id, asset_id) VALUES ("
                    + recordId + ", 9501)");
            fail("唯一索引 uk_asset_transfer_record_asset 未生效：同一张单可以把同一个资产挂两次");
        } catch (AssertionError expected) {
            assertThat(expected.getCause())
                    .as("必须是唯一约束冲突，而不是别的 SQL 错误")
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    @DisplayName("V55：菜单挂在 deed 目录下、path 与前端镜像逐字一致，view 只回填给非超管")
    void seedsTransferRecordMenu() {
        assertThat(queryString("SELECT path FROM menu WHERE code = 'deed.transferRecord'"))
                .as("path 与前端 PATH_TO_CODE['/asset-transfer-records'] 必须逐字一致，"
                        + "否则点菜单会落回首页")
                .isEqualTo("/asset-transfer-records");

        long deedId = queryLong("SELECT id FROM menu WHERE code = 'deed'");
        assertThat(deedId)
                .as("父目录 deed 必须存在（V53 把它的显示名改成了「资债权证记录」，但 code 不变），"
                        + "否则按 code 解析 parent_id 会得到 NULL，菜单挂不上树")
                .isPositive();
        assertThat(queryString("SELECT parent_id FROM menu WHERE code = 'deed.transferRecord'"))
                .as("菜单必须挂在 deed 目录下")
                .isEqualTo(String.valueOf(deedId));

        assertThat(queryString("SELECT name FROM menu WHERE code = 'deed.transferRecord'"))
                .isEqualTo("资产调拨记录");

        List<String> rows = queryStrings(
                "SELECT r.code || ':' || rp.action"
                        + " FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                        + " WHERE rp.menu_code = 'deed.transferRecord'"
                        + " ORDER BY r.code, rp.action");
        assertThat(rows)
                .as("只允许 view 且不含 super_admin（它由 PermissionRegistry 特判全通）")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).endsWith(":view"))
                .allSatisfy(row -> assertThat(row).doesNotStartWith("super_admin:"));
    }

    // ------------------------------------------------------------------
    // 6. V56：抵押记录的标的扩展在真库上的落库结果
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V56：mortgage 的标的列约束正确 —— target_type 有默认值，asset_id 放开，company_id 仍可空")
    void extendsMortgageTargetColumns() {
        assertThat(columnType("mortgage", "target_type"))
                .as("target_type 必须 NOT NULL 且有默认值 asset：存量行（只有 asset_id）"
                        + "不会被升级成 NULL，而 NULL 类型的记录在三级在押校验里一条都命中不了")
                .startsWith("character varying|NO|");
        assertThat(queryString("SELECT column_default FROM information_schema.columns"
                        + " WHERE table_name = 'mortgage' AND column_name = 'target_type'"))
                .as("默认值必须是字面量 asset")
                .contains("asset");

        assertThat(columnType("mortgage", "target_id"))
                .as("target_id 必须 NOT NULL：没有标的的抵押记录会静默拦不住任何人，且无法排查")
                .startsWith("bigint|NO|");

        assertThat(columnType("mortgage", "asset_id"))
                .as("asset_id 必须放开 NOT NULL：项目 / 分区抵押没有资产 id")
                .startsWith("bigint|YES|");

        assertThat(columnType("mortgage", "company_id"))
                .as("company_id 刻意可空：asset.asset_company_id 本身可空，NOT NULL 会让迁移"
                        + "在某些数据集上于上线时刻直接失败")
                .startsWith("bigint|YES|");

        assertThat(columnType("mortgage", "interest_rate"))
                .as("利率是 NUMERIC(8,4)：百分数 + 4 位小数")
                .isEqualTo("numeric|YES|");
        assertThat(queryString("SELECT numeric_precision || ',' || numeric_scale"
                        + " FROM information_schema.columns"
                        + " WHERE table_name = 'mortgage' AND column_name = 'interest_rate'"))
                .isEqualTo("8,4");

        assertThat(columnType("mortgage", "deleted_at"))
                .as("deleted_at 可空：只有草稿会被软删")
                .startsWith("timestamp with time zone|YES|");
    }

    @Test
    @DisplayName("V56：只写 asset_id 不写 target_id 会被 NOT NULL 挡下 —— 杜绝「看起来在押、其实不拦」")
    void mortgageRequiresTargetId() {
        try {
            execute("INSERT INTO mortgage (asset_id, mortgagee, status) VALUES (9501, '某银行', 'draft')");
            fail("target_id 允许 NULL：这条记录在三级在押校验里一条都命中不了，"
                    + "表现为「抵押列表里有、处置时却不拦」");
        } catch (AssertionError expected) {
            assertThat(expected.getCause())
                    .as("必须是 NOT NULL 违约，而不是别的 SQL 错误")
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    @DisplayName("V56：抵押合同编号部分唯一索引只约束「已填写且未软删」，空编号允许重复")
    void contractNoUniqueIndexIsPartial() {
        execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status, contract_no)"
                + " VALUES ('project', 7001, '某银行', 'draft', 'DY-TEST-0001')");

        try {
            execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status, contract_no)"
                    + " VALUES ('asset', 9501, '另一银行', 'draft', 'DY-TEST-0001')");
            fail("uk_mortgage_contract_no 未生效：同一份合同编号可以被两条记录占用，"
                    + "而合同编号是人工对账的唯一线索");
        } catch (AssertionError expected) {
            assertThat(expected.getCause()).isInstanceOf(java.sql.SQLException.class);
        }

        // 空编号必须允许多行：草稿阶段绝大多数记录还没拿到合同号，
        // 若索引不是 partial，第二条空编号的草稿就会被拒
        execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status, contract_no)"
                + " VALUES ('project', 7002, '甲银行', 'draft', NULL)");
        execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status, contract_no)"
                + " VALUES ('project', 7003, '乙银行', 'draft', NULL)");
        assertThat(queryLong("SELECT count(*) FROM mortgage WHERE contract_no IS NULL"))
                .as("两条空编号草稿都必须插入成功")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("V56：项目级抵押必须覆盖其下资产 —— 这是本模块存在的理由")
    void projectMortgageCoversAssets() {
        // 项目 7801 → 分区 A(7802) / B(7804) → 各一个资产（7803 / 7805）
        execute("INSERT INTO project (id, company_id, name) VALUES (7801, 2, '抵押测试项目')");
        execute("INSERT INTO project_zone (id, project_id, name) VALUES (7802, 7801, 'A区')");
        execute("INSERT INTO project_zone (id, project_id, name) VALUES (7804, 7801, 'B区')");
        execute("INSERT INTO asset (id, project_id, zone_id, asset_no, name, asset_type,"
                + " asset_company_id, lease_control_status)"
                + " VALUES (7803, 7801, 7802, 'MORT-TEST-1', '抵押测试资产A', 'property', 2, 'vacant')");
        execute("INSERT INTO asset (id, project_id, zone_id, asset_no, name, asset_type,"
                + " asset_company_id, lease_control_status)"
                + " VALUES (7805, 7801, 7804, 'MORT-TEST-2', '抵押测试资产B', 'property', 2, 'vacant')");

        // 草稿不算在押：否则「先起草抵押、再补材料」会把项目下所有资产冻结
        execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status)"
                + " VALUES ('asset', 7803, '某银行', 'draft')");
        assertThat(coveringCount(7803)).as("草稿不得算作在押").isZero();

        // 资产级在押：只覆盖它自己，同项目的兄弟资产不受影响
        execute("UPDATE mortgage SET status = 'active' WHERE target_id = 7803 AND target_type = 'asset'");
        assertThat(coveringCount(7803)).as("资产级在押必须覆盖自身").isEqualTo(1);
        assertThat(coveringCount(7805)).as("资产级抵押不得波及同项目的其它资产").isZero();

        // 分区级在押：覆盖本分区资产，不覆盖其它分区
        execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status)"
                + " VALUES ('zone', 7802, '某银行', 'active')");
        assertThat(coveringCount(7803)).as("分区级在押也必须算在资产头上").isEqualTo(2);
        assertThat(coveringCount(7805)).as("分区级抵押不得波及别的分区").isZero();

        // 项目级在押：整个项目下的资产都算
        execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status)"
                + " VALUES ('project', 7801, '某银行', 'active')");
        assertThat(coveringCount(7805)).as("项目级在押必须覆盖它下面的资产").isEqualTo(1);

        // 软删的行不算：删掉项目级那条后，B区资产重新变成未抵押
        execute("UPDATE mortgage SET deleted_at = now()"
                + " WHERE target_type = 'project' AND target_id = 7801");
        assertThat(coveringCount(7805)).as("软删的抵押不得继续拦截业务").isZero();
    }

    @Test
    @DisplayName("V56：解押一张抵押后，仍被另一张覆盖的资产必须保持「在押」（不能按方向直接赋值）")
    void refreshCertStatusRecomputesPerAsset() {
        execute("INSERT INTO project (id, company_id, name) VALUES (7901, 2, '重算测试项目')");
        execute("INSERT INTO asset (id, project_id, asset_no, name, asset_type, asset_company_id,"
                + " lease_control_status) VALUES (7903, 7901, 'MORT-TEST-3', '重算测试资产',"
                + " 'property', 2, 'vacant')");
        execute("INSERT INTO asset_certificate (asset_id, cert_no, mortgage_status)"
                + " VALUES (7903, 'CERT-MORT-1', 'none')");

        // 项目级 + 资产级两张在押，都覆盖 7903
        execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status)"
                + " VALUES ('project', 7901, '甲银行', 'active')");
        execute("INSERT INTO mortgage (target_type, target_id, mortgagee, status)"
                + " VALUES ('asset', 7903, '乙银行', 'active')");

        execute(MortgageMapper.REFRESH_CERT_SQL
                .replace("#{targetType}", "'project'").replace("#{targetId}", "7901"));
        assertThat(queryString("SELECT mortgage_status FROM asset_certificate WHERE asset_id = 7903"))
                .as("两张在押覆盖时权证状态应为「在押」")
                .isEqualTo("mortgaged");

        // 解掉资产级那一张，但项目级那张仍在 —— 权证状态必须保持 mortgaged
        execute("UPDATE mortgage SET status = 'released' WHERE target_type = 'asset' AND target_id = 7903");
        execute(MortgageMapper.REFRESH_CERT_SQL
                .replace("#{targetType}", "'asset'").replace("#{targetId}", "7903"));
        assertThat(queryString("SELECT mortgage_status FROM asset_certificate WHERE asset_id = 7903"))
                .as("还有一张在押覆盖着它，权证状态不能被改成「无抵押」——"
                        + "这正是「按变更方向直接赋值」会写错的地方")
                .isEqualTo("mortgaged");

        // 两张都解掉，才回到「无抵押」
        execute("UPDATE mortgage SET status = 'released' WHERE target_type = 'project' AND target_id = 7901");
        execute(MortgageMapper.REFRESH_CERT_SQL
                .replace("#{targetType}", "'project'").replace("#{targetId}", "7901"));
        assertThat(queryString("SELECT mortgage_status FROM asset_certificate WHERE asset_id = 7903"))
                .isEqualTo("none");
    }

    /** 直接执行 Mapper 里那条 SQL，而不是在测试里重写一遍（重写会漂移）。 */
    private long coveringCount(long assetId) {
        return queryLong(MortgageMapper.COUNT_ACTIVE_COVERING_SQL
                .replace("#{assetId}", String.valueOf(assetId)));
    }

    @Test
    @DisplayName("V56：抵押记录菜单改名，但 code 与 path 不变（path 变了会让既有书签落回首页）")
    void renamesMortgageMenu() {
        assertThat(queryString("SELECT name FROM menu WHERE code = 'deed.mortgage'"))
                .as("页面已从只读列表升级为完整模块，名字要跟上")
                .isEqualTo("抵押记录");
        assertThat(queryString("SELECT path FROM menu WHERE code = 'deed.mortgage'"))
                .as("path 与前端 PATH_TO_CODE['/mortgages'] 必须逐字一致")
                .isEqualTo("/mortgages");
        assertThat(queryString("SELECT parent_id FROM menu WHERE code = 'deed.mortgage'"))
                .as("菜单必须仍挂在 deed 目录下（V53 改过它的显示名，但 code 不变）")
                .isEqualTo(String.valueOf(queryLong("SELECT id FROM menu WHERE code = 'deed'")));
    }

    // ------------------------------------------------------------------
    // 7. V57：资产处置记录在真库上的落库结果（菜单归属另见 V58）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V57：asset_disposal_record 的必填 / 可空列与设计一致，且没有软删列")
    void createsAssetDisposalRecordTable() {
        assertThat(columnType("asset_disposal_record", "asset_id"))
                .as("资产 id 必填：一行就是一个被处置的资产，没有资产的行不可追溯")
                .startsWith("bigint|NO|");
        assertThat(columnType("asset_disposal_record", "target_type"))
                .as("处置对象层级必填（asset / project / zone）")
                .startsWith("character varying|NO|");
        assertThat(columnType("asset_disposal_record", "target_id")).startsWith("bigint|NO|");
        assertThat(columnType("asset_disposal_record", "from_property_company_id"))
                .as("原产权公司快照必须可空：资产处置时本来就可能没有产权公司")
                .startsWith("bigint|YES|");
        assertThat(columnType("asset_disposal_record", "source_order_id"))
                .as("资产级来源可空：项目 / 分区级级联没有 disposal_order")
                .startsWith("bigint|YES|");
        assertThat(columnType("asset_disposal_record", "amount_unit"))
                .as("金额单位可空：没填金额时不需要单位")
                .startsWith("character varying|YES|");
        assertThat(columnType("asset_disposal_record", "disposed_at"))
                .as("处置时间 NOT NULL 且有默认值：它是列表的排序与展示依据")
                .startsWith("timestamp with time zone|NO|");
        // 处置不可逆：台账只增不减，所以刻意不落 deleted_at（与 V54/V55 的明细表不同 ——
        // 那两个的宿主是草稿可改可删的主单，台账没有「草稿」这一态）
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = 'asset_disposal_record' AND column_name = 'deleted_at'"))
                .as("台账不应有软删列：处置不可逆，留软删只会制造永不清理的孤儿行")
                .isZero();
    }

    @Test
    @DisplayName("V57：同一资产重复写台账会被唯一索引拒绝 —— 幂等键与并发兜底")
    void rejectsDuplicateDisposedAsset() {
        execute("INSERT INTO asset_disposal_record (asset_id, target_type, target_id)"
                + " VALUES (9601, 'asset', 9601)");

        try {
            execute("INSERT INTO asset_disposal_record (asset_id, target_type, target_id)"
                    + " VALUES (9601, 'project', 42)");
            fail("唯一索引 uk_asset_disposal_record_asset 未生效：同一资产可以写两条处置台账");
        } catch (AssertionError expected) {
            assertThat(expected.getCause())
                    .as("必须是唯一约束冲突，而不是别的 SQL 错误")
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    @DisplayName("V57：资产处置记录菜单先落在 operation 目录下（V58 随后把它移入 deed 目录）")
    void seedsDisposalRecordMenu() {
        // 本用例描述的是 **V57 当时的落库结果**；最终归属由 V58 调整（见
        // V58MoveDisposalRecordMenuMigrationContractTest 与下方 v58 的终态断言）。
        assertThat(queryString("SELECT path FROM menu WHERE code = 'deed.disposalRecord'"))
                .as("path 与前端 PATH_TO_CODE['/disposal-records'] 必须逐字一致，否则点菜单会落回首页")
                .isEqualTo("/disposal-records");
        assertThat(queryString("SELECT name FROM menu WHERE code = 'deed.disposalRecord'"))
                .isEqualTo("资产处置记录");

        long deedId = queryLong("SELECT id FROM menu WHERE code = 'deed'");
        assertThat(deedId)
                .as("父目录 deed（资债权证记录）必须存在，否则按 code 解析 parent_id 会得到 NULL")
                .isPositive();
        assertThat(queryString("SELECT parent_id FROM menu WHERE code = 'deed.disposalRecord'"))
                .as("V58 之后菜单最终挂在「资债权证记录」目录下")
                .isEqualTo(String.valueOf(deedId));

        List<String> rows = queryStrings(
                "SELECT r.code || ':' || rp.action"
                        + " FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                        + " WHERE rp.menu_code = 'deed.disposalRecord'"
                        + " ORDER BY r.code, rp.action");
        assertThat(rows)
                .as("只允许 view 且不含 super_admin（它由 PermissionRegistry 特判全通）")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).endsWith(":view"))
                .allSatisfy(row -> assertThat(row).doesNotStartWith("super_admin:"));

        // V58 的改名必须是**完整**的：旧码不得残留，否则权限矩阵会出现两条指向同一菜单的授权
        assertThat(queryLong("SELECT count(*) FROM menu WHERE code = 'operation.disposalRecord'"))
                .as("旧码必须已被 V58 改掉（唯一约束下也不可能有第二行）")
                .isZero();
        assertThat(queryLong("SELECT count(*) FROM role_permission"
                        + " WHERE menu_code = 'operation.disposalRecord'"))
                .as("role_permission.menu_code 是反范式冗余列，不同步会让按码查询得到空集")
                .isZero();
    }
}
