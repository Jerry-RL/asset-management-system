package com.ams.modules.asset;

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
 * V50 迁移的契约守卫（分区楼层，project_zone_floor）。
 *
 * <p>每条断言对应一类真实事故：
 * <ol>
 *   <li>表名 / 列名与实体 {@code @TableName("project_zone_floor")} 漂移 ——
 *       MyBatis-Plus 会去查一张不存在的表，运行期直接报错；</li>
 *   <li>漏掉「同分区楼层号唯一」的部分唯一索引 —— 楼层栏出现两个「3F」，
 *       使用者无从判断该改哪一个；</li>
 *   <li>漏掉回填语句 —— 升级后每个分区的楼层 Tab 都是空的，而资产明明带着 floor_no；</li>
 *   <li>误改既有表或插菜单 —— 本迁移是纯 expand（楼层复用 asset.project:* 权限码），
 *       动既有表就是 DBA 会拦住的破坏性变更。</li>
 * </ol>
 *
 * <p>只断言「会真正执行的语句」（去掉 {@code --} 注释）：否则一句解释性注释就能让负向断言误红，
 * 或让正向断言空转通过。
 */
class V50ProjectZoneFloorMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V50__project_zone_floor.sql";

    /** 实体 @TableName 的值：两侧任改一处都会在这里变红。 */
    private static final String TABLE = "project_zone_floor";

    private static String SQL;

    @BeforeAll
    static void loadMigration() {
        SQL = readMigration();
    }

    private static String readMigration() {
        try (InputStream in =
                V50ProjectZoneFloorMigrationContractTest.class.getResourceAsStream(MIGRATION_PATH)) {
            assertThat(in).as("V50 迁移文件必须存在（放在 src/main/resources/db/migration 下）").isNotNull();
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
    @DisplayName("建表包含全部字段（编号 / 名称 / 备注 + 审计 + 软删）")
    void createsFloorTable() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + TABLE);
        assertThat(sql)
                .as("字段必须齐全：zone_id 是归属，floor_no 是资产的落层键，deleted_at 是软删位")
                .contains("zone_id", "floor_no", "name", "remark",
                        "created_at", "updated_at", "created_by", "updated_by", "deleted_at");
    }

    @Test
    @DisplayName("楼层面积与排序刻意不落列：面积取资产汇总，排序取 floor_no 升序")
    void hasNoManualAreaOrSortColumn() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("人工维护的面积会与「资产实际面积」形成两套数字（与 project_zone 同口径）")
                .doesNotContain("area ");
        assertThat(sql)
                .as("楼层顺序按 floor_no 即物理顺序，另设 sort 会制造第二个矛盾来源")
                .doesNotContain("sort ");
    }

    @Test
    @DisplayName("同分区楼层号唯一：部分唯一索引 + 只约束未软删行")
    void enforcesUniqueFloorNoPerZone() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("缺了它楼层栏会出现两个「3F」，使用者无从判断改哪一个")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_" + TABLE + "_zone_no")
                .contains("(" + "zone_id, floor_no)")
                .as("只约束未软删的行，否则软删掉的楼层号再也无法重新录入")
                .contains("WHERE deleted_at IS NULL");
    }

    // ------------------------------------------------------------------
    // 回填
    // ------------------------------------------------------------------

    @Test
    @DisplayName("存量资产已用到的楼层号必须回填成楼层记录（否则楼层 Tab 全空）")
    void backfillsFloorsFromExistingAssets() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("回填来源是 asset.floor_no：楼层此前只以这个整数属性存在")
                .contains("INSERT INTO " + TABLE)
                .contains("SELECT a.zone_id", "a.floor_no")
                .contains("FROM asset a");
        assertThat(sql)
                .as("回填必须幂等：重复执行不得插入重复楼层")
                .contains("ON CONFLICT DO NOTHING");
        assertThat(sql)
                .as("只回填落在分区内、未删除的资产：悬空 floor_no 不该造出楼层")
                .contains("a.zone_id IS NOT NULL", "a.floor_no IS NOT NULL", "a.deleted_at IS NULL");
    }

    // ------------------------------------------------------------------
    // 结构不变量：纯 expand
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不修改既有表（纯 expand：只加表 / 加索引 / 给自己回填）")
    void doesNotAlterExistingTables() {
        assertThat(sqlWithoutComments(SQL))
                .as("改既有表是 DBA 会拦住的破坏性变更；本迁移不需要，也绝不允许")
                .doesNotContain("ALTER TABLE");
    }

    @Test
    @DisplayName("不新增菜单 / 授权：楼层复用 asset.project:* 权限码")
    void doesNotSeedMenuOrPermission() {
        String sql = sqlWithoutComments(SQL);

        assertThat(sql)
                .as("楼层沿用项目分区的权限码，新开权限码会让既有角色配置漏配")
                .doesNotContain("INSERT INTO menu")
                .doesNotContain("INSERT INTO sys_role_menu")
                .doesNotContain("INSERT INTO sys_menu")
                .doesNotContain("INSERT INTO sys_role_permission");
    }

    @Test
    @DisplayName("唯一的 DML 是给本表回填：写别的表就是复制粘贴串了迁移")
    void onlyInsertsIntoOwnTable() {
        String sql = sqlWithoutComments(SQL);

        int inserts = sql.split("INSERT\\s+INTO", -1).length - 1;
        assertThat(inserts).as("V50 只有一条回填语句").isEqualTo(1);
    }
}
