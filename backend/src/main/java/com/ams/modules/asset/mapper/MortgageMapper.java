package com.ams.modules.asset.mapper;

import com.ams.modules.asset.entity.Mortgage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 抵押记录 Mapper（V56 起支撑项目 / 分区 / 资产三级标的）。
 *
 * <p>下面三条语句都是**跨表判定**（mortgage × asset），用注解写在 Mapper 上而不是
 * 拼在 Service 里：它们的核心是「一个资产被哪些层级的抵押覆盖」这一条规则，
 * 散进 Service 就会出现第二份实现，而两份实现漂移的表现是
 * 「列表说没在押、处置却被拦下」这类只有线上才暴露的问题。
 *
 * <p><b>SQL 抽成 {@code public static final} 常量</b>（而不是直接写在注解里）：
 * {@code MigrationChainPostgresTest} 要在真实 PostgreSQL 上执行这几条语句来证明
 * 「项目级抵押确实覆盖了它下面的资产」。把 SQL 抄进测试会得到一份会漂移的副本 ——
 * 测试绿着，而注解里的 SQL 已经改了。
 */
@Mapper
public interface MortgageMapper extends BaseMapper<Mortgage> {

    /**
     * 「该资产被这条抵押覆盖」的判定条件。
     *
     * <p>三级任一命中即覆盖：项目或分区被抵押时，其下的资产同样受抵押权约束 ——
     * 这正是本模块要扩展的原因（另建一张表会让这类抵押对前置校验完全不可见）。
     *
     * <p>{@code zone_id} / {@code project_id} 为 NULL 时比较结果为 NULL，自然不命中，
     * 不需要额外的 {@code IS NOT NULL} 兜底。
     */
    String COVERING_CONDITION =
            "((m.target_type = 'asset'   AND m.target_id = a.id) "
            + " OR (m.target_type = 'zone'    AND m.target_id = a.zone_id) "
            + " OR (m.target_type = 'project' AND m.target_id = a.project_id))";

    /**
     * 该资产是否被**在押**记录覆盖（自身 / 所属分区 / 所属项目 任一层级）。
     *
     * <p>草稿（{@code status = 'draft'}）与已软删的行都不算：草稿只是用户的 working copy，
     * 把它算成在押会让「先起草再补材料」这个正常流程变成「资产被冻结」。
     *
     * <p>写成 {@code count} 而不是先查列表：调用方（5 处前置校验）只关心真假，
     * 而资产可能是几千个循环里的一员，返回整行会让每次校验都拖回一批用不上的列。
     */
    String COUNT_ACTIVE_COVERING_SQL =
            "SELECT count(*) FROM mortgage m JOIN asset a ON a.id = #{assetId}"
            + " WHERE m.status = 'active' AND m.deleted_at IS NULL AND " + COVERING_CONDITION;

    /**
     * 覆盖该资产的全部抵押记录（含草稿与已解押），资产档案用。
     *
     * <p>不过滤 {@code status}：档案要呈现完整的抵押历史 —— 已解押的记录同样是
     * 「这一物过去发生过什么」的一部分（与档案里其它段一律含全状态一致）。
     * 只过滤软删：草稿删掉后不该在档案里留下痕迹。
     */
    String SELECT_COVERING_SQL =
            "SELECT m.* FROM mortgage m JOIN asset a ON a.id = #{assetId}"
            + " WHERE m.deleted_at IS NULL AND " + COVERING_CONDITION
            + " ORDER BY m.id DESC";

    /**
     * 重算「该标的所覆盖的资产」的权证抵押状态。
     *
     * <p>为什么不是「把这些资产的权证全置为 mortgaged」：一个资产可能同时被
     * 项目级与资产级两张抵押覆盖，解掉其中一张时它仍然是**在押** ——
     * 所以每行的新状态必须按该资产重新判定一次（{@code EXISTS} 子查询），
     * 而不是按本次变更的方向直接赋值。
     *
     * <p>为什么用一条 UPDATE 而不是「查出资产 → 逐个同步」：项目级抵押可能覆盖成百上千个资产，
     * 逐个 {@code selectById + updateById} 会把一次解押变成一个上千次的循环。
     * 影响范围由标的类型在 SQL 里自解释（asset → 自身、zone → 该分区、project → 该项目），
     * 也避免了在 Java 里再写一份同样的三级展开。
     *
     * <p>{@code CAST(#{targetType} AS VARCHAR)}：不 cast 时 PG 无法推断参数类型，
     * 会以「operator does not exist: unknown = text」失败。
     */
    String REFRESH_CERT_SQL =
            "UPDATE asset_certificate c "
            + "   SET mortgage_status = CASE WHEN EXISTS ("
            + "         SELECT 1 FROM mortgage m JOIN asset a ON a.id = c.asset_id "
            + "          WHERE m.status = 'active' AND m.deleted_at IS NULL "
            + "            AND " + COVERING_CONDITION
            + "       ) THEN 'mortgaged' ELSE 'none' END "
            + " WHERE c.asset_id IN ("
            + "         SELECT id FROM asset WHERE "
            + "             (CAST(#{targetType} AS VARCHAR) = 'asset'   AND id = #{targetId}) "
            + "          OR (CAST(#{targetType} AS VARCHAR) = 'zone'    AND zone_id = #{targetId}) "
            + "          OR (CAST(#{targetType} AS VARCHAR) = 'project' AND project_id = #{targetId}))";

    @Select(COUNT_ACTIVE_COVERING_SQL)
    long countActiveCoveringAsset(@Param("assetId") Long assetId);

    @Select(SELECT_COVERING_SQL)
    List<Mortgage> selectCoveringAsset(@Param("assetId") Long assetId);

    @Update(REFRESH_CERT_SQL)
    int refreshCertMortgageStatus(@Param("targetType") String targetType,
                                  @Param("targetId") Long targetId);
}
