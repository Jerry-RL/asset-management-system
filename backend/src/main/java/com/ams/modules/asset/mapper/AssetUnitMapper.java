package com.ams.modules.asset.mapper;

import com.ams.modules.asset.entity.AssetUnit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AssetUnitMapper extends BaseMapper<AssetUnit> {

    /**
     * 行锁：并发占用同一单元时串行化「校验 → 插入」。
     *
     * <p>没有这把锁，两个事务会同时通过服务层校验，最后由 EXCLUDE 约束让其中一个失败，
     * 用户体验是「随机报冲突」而不是「明确提示已被占用」。
     *
     * @return 单元 ID；不存在或已软删除返回 {@code null}
     */
    @Select("SELECT id FROM asset_unit WHERE id = #{id} AND deleted_at IS NULL FOR UPDATE")
    Long lockForUpdate(@Param("id") Long id);

    /**
     * 按派生视图同步单元状态（单语句集合更新，避免逐单元 N+1）。
     *
     * <p>视图 {@code v_unit_lease_status} 是状态真源，{@code unit_status} 只是物化缓存；
     * {@code IS DISTINCT FROM} 保证无变化时不产生写入与版本推进。
     *
     * @return 实际更新的单元数
     */
    @Update("UPDATE asset_unit u SET unit_status = v.derived_status, updated_at = now() "
            + "FROM v_unit_lease_status v "
            + "WHERE v.unit_id = u.id AND u.asset_id = #{assetId} AND u.deleted_at IS NULL "
            + "AND u.unit_status IS DISTINCT FROM v.derived_status")
    int syncUnitStatus(@Param("assetId") Long assetId);
}
