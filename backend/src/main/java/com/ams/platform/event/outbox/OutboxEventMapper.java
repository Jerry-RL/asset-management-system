package com.ams.platform.event.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 领取待投递事件（单语句完成「选取 + 租约」）。
     *
     * <p>两点设计：
     * <ol>
     *   <li>{@code FOR UPDATE SKIP LOCKED} —— 多实例并发领取时各取各的，不互相阻塞、不重复领取；</li>
     *   <li>领取时把 {@code next_retry_at} 前推一个租约期 —— 实例领取后崩溃，租约到期后
     *       其他实例可接手；<b>不新增「dispatching」状态</b>，避免多一个状态与卡死恢复逻辑。</li>
     * </ol>
     */
    @Update("UPDATE domain_event_outbox o "
            + "SET next_retry_at = now() + make_interval(secs => #{leaseSeconds}) "
            + "WHERE o.id IN ("
            + "    SELECT id FROM domain_event_outbox "
            + "     WHERE status = 'pending' AND next_retry_at <= now() "
            + "     ORDER BY id "
            + "     LIMIT #{limit} "
            + "     FOR UPDATE SKIP LOCKED) "
            + "RETURNING o.*")
    List<OutboxEvent> claimPending(@Param("limit") int limit,
                                   @Param("leaseSeconds") int leaseSeconds);

    /** 投递成功。 */
    @Update("UPDATE domain_event_outbox "
            + "SET status = 'done', dispatched_at = now(), error = NULL "
            + "WHERE id = #{id}")
    int markDone(@Param("id") Long id);

    /**
     * 投递失败：累加重试次数并按下次重试时刻前推；超限则置 {@code dead}。
     *
     * <p>{@code status} 由调用方决定（重试或超限），避免把重试策略写死在 SQL 里。
     */
    @Update("UPDATE domain_event_outbox "
            + "SET status = #{status}, "
            + "    retry_count = retry_count + 1, "
            + "    next_retry_at = now() + make_interval(secs => #{backoffSeconds}), "
            + "    dispatched_at = now(), "
            + "    error = #{error} "
            + "WHERE id = #{id}")
    int markRetried(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("backoffSeconds") int backoffSeconds,
                    @Param("error") String error);

    /** 按事件 ID 查（排障与测试）。 */
    @Select("SELECT * FROM domain_event_outbox WHERE event_id = #{eventId}")
    OutboxEvent findByEventId(@Param("eventId") String eventId);

    /** 健康度统计：dead 必须为 0（设计 §14 验收 SQL ⑤）。 */
    @Select("SELECT status, count(*) AS cnt FROM domain_event_outbox GROUP BY status")
    List<java.util.Map<String, Object>> countByStatus();
}
