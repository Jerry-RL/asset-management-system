package com.ams.modules.asset.mapper;

import com.ams.modules.asset.entity.ProjectZone;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分区 Mapper。
 *
 * <p>两个 {@code selectActive*} 用显式 SQL 而不是 {@code LambdaQueryWrapper.isNull("deleted_at")}：
 * 它们同时承担「存在性判定」职责，写成一句 SQL 才能让「查出来的行」与「判定的行」必然一致，
 * 不会出现「判定用 A 条件、取数用 B 条件」的偏差。{@code ProjectZone} 实体刻意不映射
 * {@code deleted_at}（它是分区写接口的请求体类型），所以过滤只能写在 SQL 里。
 */
@Mapper
public interface ProjectZoneMapper extends BaseMapper<ProjectZone> {

    /** 按 id 取未软删的分区；不存在或已软删返回 {@code null}。 */
    @Select("SELECT * FROM project_zone WHERE id = #{zoneId} AND deleted_at IS NULL")
    ProjectZone selectActiveById(@Param("zoneId") Long zoneId);

    /**
     * 取「属于该项目且未软删」的分区；不存在 / 已软删 / 属于别的项目都返回 {@code null}。
     * 归属校验与取数合并成一次查询，避免「防跨项目写入」这类安全判断散落在两处。
     */
    @Select("SELECT * FROM project_zone WHERE id = #{zoneId} AND project_id = #{projectId} "
            + "AND deleted_at IS NULL")
    ProjectZone selectActiveInProject(@Param("projectId") Long projectId, @Param("zoneId") Long zoneId);
}
