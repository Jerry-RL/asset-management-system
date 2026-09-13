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
     *
     * <p><b>为什么是 {@code @Select} 而不是 {@code @Update}（即便语句本身是 UPDATE）</b>：
     * 这条语句带 {@code RETURNING o.*}，要取回被领取的行。MyBatis 按注解决定执行方式，
     * {@code @Update} 只接受 {@code int} / {@code void} 这类计数返回 —— 配 {@code List} 会在
     * <b>每次调用时</b>抛
     * {@code BindingException: Mapper method '...claimPending' has an unsupported return type: java.util.List}。
     * 这个错误发生在 mapper 绑定层，{@code OutboxDispatcherTest} 把 mapper 整个 mock 掉，
     * <b>看不见它</b> —— 该 bug 曾以这种形态存活（兜底重试每次 tick 都抛错、失败事件永不重试）。
     * 验证方式只能走真库：往 {@code domain_event_outbox} 插一行 {@code pending}
     * （{@code event_type} 用一个未登记的类型，避免触发真实监听器），等一次调度 tick，
     * 看到「redelivery failed, will retry」即证明领取成功；插行前若报上述 BindingException 则未修复。
     * 不能拆成「先 SELECT 再 UPDATE」：那样 {@code FOR UPDATE SKIP LOCKED} 与租约前推
     * 就不在同一语句里，两个实例会领到同一批行。
     *
     * <p>配合 {@code @Select} 的一个注意点：MyBatis 会把查询语句放进 SqlSession 本地缓存，
     * 而本语句按同一组入参反复调用。当前调用方（{@code OutboxDispatcher} 的定时任务）没有
     * 事务，每次调用各自开一个 SqlSession，不会命中缓存；若将来给调用链加上事务，
     * 需要显式 {@code flushCache}，否则第二次领取会拿到上一次的结果。
     */
    @Select("UPDATE domain_event_outbox o "
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
