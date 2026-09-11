package com.ams.platform.compensation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProcessStepMapper extends BaseMapper<ProcessStep> {

    /**
     * 起始步骤记录（幂等 upsert）。
     *
     * <p><b>必须 upsert 而非 insert</b>：步骤记录写在独立事务（REQUIRES_NEW）中，
     * 外层事务回滚后记录仍在；若上游重试整个流程，同一步骤会再次 begin，
     * insert 会撞 {@code uq_process_step} 唯一键，而 upsert 直接复用该行并把状态重置为 running。
     */
    @Update("INSERT INTO process_step "
            + "(process_type, biz_id, step_no, step_name, status, compensate_kind, created_at, updated_at) "
            + "VALUES (#{processType}, #{bizId}, #{stepNo}, #{stepName}, "
            + "        #{status}, #{compensateKind}, now(), now()) "
            + "ON CONFLICT (process_type, biz_id, step_no) DO UPDATE SET "
            + "  step_name = EXCLUDED.step_name, "
            + "  status = EXCLUDED.status, "
            + "  compensate_kind = EXCLUDED.compensate_kind, "
            + "  error = NULL, "
            + "  updated_at = now()")
    int upsertBegin(@Param("processType") String processType,
                    @Param("bizId") Long bizId,
                    @Param("stepNo") Integer stepNo,
                    @Param("stepName") String stepName,
                    @Param("status") String status,
                    @Param("compensateKind") String compensateKind);

    /** 按业务键取步骤记录（begin 之后取回 id）。 */
    @Select("SELECT * FROM process_step "
            + "WHERE process_type = #{processType} AND biz_id = #{bizId} AND step_no = #{stepNo}")
    ProcessStep findByKey(@Param("processType") String processType,
                          @Param("bizId") Long bizId,
                          @Param("stepNo") Integer stepNo);

    /** 某流程的全部步骤（按执行顺序，排障用）。 */
    @Select("SELECT * FROM process_step "
            + "WHERE process_type = #{processType} AND biz_id = #{bizId} ORDER BY step_no")
    List<ProcessStep> listByBiz(@Param("processType") String processType,
                                @Param("bizId") Long bizId);

    /** 待处置清单（failed / manual）——待办与运维入口。 */
    @Select("SELECT * FROM process_step WHERE status IN ('failed', 'manual') "
            + "ORDER BY updated_at DESC NULLS LAST, id DESC LIMIT #{limit}")
    List<ProcessStep> listOpenIssues(@Param("limit") int limit);
}
