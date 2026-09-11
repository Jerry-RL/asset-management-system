package com.ams.modules.asset.mapper;

import com.ams.modules.asset.dto.LeaseStatusSnapshot;
import com.ams.modules.asset.entity.AssetOccupancy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetOccupancyMapper extends BaseMapper<AssetOccupancy> {

    /**
     * 与 {@code [from, to)} 重叠的生效占用（含开放区间）。
     *
     * <p><b>不过滤 {@code biz_status}</b>：可用性口径下「审批中预留」同样算被占，
     * 过滤掉它会让并发超租窗口重新出现（ADR-0020 决策 E）。
     *
     * <p>注意：{@code to} 必须传具体日期。无期限占用请由调用方传
     * {@link com.ams.modules.asset.service.AssetOccupancyService#OPEN_ENDED}，
     * 避免 JDBC 传 {@code NULL} 导致 PostgreSQL 无法推断 {@code daterange} 参数类型。
     */
    @Select("SELECT * FROM asset_occupancy ao "
            + "WHERE ao.asset_unit_id = #{unitId} AND ao.exclusive "
            + "AND daterange(ao.date_from, ao.date_to, '[)') "
            + "    && daterange(CAST(#{from} AS date), CAST(#{to} AS date), '[)')")
    List<AssetOccupancy> selectOverlapping(@Param("unitId") Long unitId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);

    /** 单元是否存在未收口占用。 */
    @Select("SELECT COUNT(*) FROM asset_occupancy ao "
            + "WHERE ao.asset_unit_id = #{unitId} "
            + "AND (ao.date_to IS NULL OR ao.date_to >= CURRENT_DATE)")
    long countOpenByUnit(@Param("unitId") Long unitId);

    /**
     * 来源单据的<b>全部</b>未收口占用（按单元排序）。
     *
     * <p><b>必须返回集合</b>：组合租赁下「一合同 N 单元」即 N 行占用（ADR-0020 决策 C），
     * 任何"取第一条"的写法都会让其余单元永久不收口、被 {@code EXCLUDE} 永久锁死
     * （见评审报告 P0-5）。释放类操作一律经此方法全量处理。
     */
    @Select("SELECT * FROM asset_occupancy ao "
            + "WHERE ao.subject_type = #{subjectType} AND ao.subject_id = #{subjectId} "
            + "AND ao.date_to IS NULL "
            + "ORDER BY ao.asset_unit_id, ao.id")
    List<AssetOccupancy> selectOpenBySubject(@Param("subjectType") String subjectType,
                                             @Param("subjectId") Long subjectId);

    /**
     * 来源单据 + 指定单元的未收口占用（部分释放 / 合同变更减面积）。
     *
     * <p>{@code LIMIT 1} 在此处是<b>可证唯一</b>的：未收口即 {@code date_to IS NULL}，
     * 其区间上界为无穷，同一单元任意两条未收口行必然重叠，会被
     * {@code ex_occupancy_unit_no_overlap} 拒绝。
     */
    @Select("SELECT * FROM asset_occupancy ao "
            + "WHERE ao.subject_type = #{subjectType} AND ao.subject_id = #{subjectId} "
            + "AND ao.asset_unit_id = #{unitId} AND ao.date_to IS NULL "
            + "ORDER BY ao.id LIMIT 1")
    AssetOccupancy selectOpenBySubjectUnit(@Param("subjectType") String subjectType,
                                           @Param("subjectId") Long subjectId,
                                           @Param("unitId") Long unitId);

    /** 该资产最后一条占用的收口日；用于派生 {@code asset.vacant_since}。 */
    @Select("SELECT MAX(ao.date_to) FROM asset_occupancy ao WHERE ao.asset_id = #{assetId}")
    LocalDate selectLastReleasedDate(@Param("assetId") Long assetId);

    /** 单个资产的派生状态（真源视图投影）。 */
    @Select("SELECT asset_id AS assetId, asset_no AS assetNo, stored_status AS storedStatus, "
            + "derived_status AS derivedStatus, lifecycle_status AS lifecycleStatus, "
            + "occupancy_ratio AS occupancyRatio "
            + "FROM v_asset_lease_status_derived WHERE asset_id = #{assetId}")
    LeaseStatusSnapshot selectDerived(@Param("assetId") Long assetId);

    /**
     * 物化列与视图不一致的资产（收敛作业输入）。
     * 只取差异行，避免全量刷新。
     */
    @Select("SELECT asset_id AS assetId, asset_no AS assetNo, stored_status AS storedStatus, "
            + "derived_status AS derivedStatus, lifecycle_status AS lifecycleStatus, "
            + "occupancy_ratio AS occupancyRatio "
            + "FROM v_asset_lease_status_reconcile LIMIT #{limit}")
    List<LeaseStatusSnapshot> selectReconcile(@Param("limit") int limit);

    /** 资产档案时间轴（复用视图，避免与报表口径分叉）。 */
    @Select("SELECT * FROM asset_occupancy ao WHERE ao.asset_id = #{assetId} "
            + "ORDER BY ao.asset_unit_id, ao.date_from")
    List<AssetOccupancy> listByAsset(@Param("assetId") Long assetId);
}
