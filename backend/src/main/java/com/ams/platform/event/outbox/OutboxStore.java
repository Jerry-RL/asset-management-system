package com.ams.platform.event.outbox;

import com.ams.platform.event.DomainEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发件箱写入服务。
 *
 * <p><b>两类事务语义，混用即出缺陷</b>：
 * <ul>
 *   <li>{@link #record} 用 {@code REQUIRED} —— 必须与业务写<b>同事务</b>：
 *       业务回滚则事件行一并回滚（不该发的事件不能发出去）；</li>
 *   <li>{@link #markDone} / {@link #markRetry} 用 {@code REQUIRES_NEW} —— 它们在
 *       {@code AFTER_COMMIT} 之后执行，原事务已提交完成，必须另起事务才能落库。</li>
 * </ul>
 *
 * <p>失败重试上限与退避通过配置注入（{@code ams.event.outbox.max-retry} /
 * {@code ams.event.outbox.backoff-seconds}），超限置 {@code dead} 并需告警。
 */
@Service
public class OutboxStore {

    private final OutboxEventMapper mapper;
    private final int maxRetry;
    private final int backoffSeconds;

    public OutboxStore(
            OutboxEventMapper mapper,
            @Value("${ams.event.outbox.max-retry:5}") int maxRetry,
            @Value("${ams.event.outbox.backoff-seconds:30}") int backoffSeconds) {
        this.mapper = mapper;
        this.maxRetry = maxRetry;
        this.backoffSeconds = backoffSeconds;
    }

    /** 记录待投递事件（与业务写同事务）。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public Long record(DomainEvent event, String payload) {
        OutboxEvent row = new OutboxEvent();
        row.setEventId(event.getEventId());
        row.setEventType(EventTypeRegistry.nameOf(event));
        row.setAggregateType(event.aggregateType());
        row.setAggregateId(event.aggregateId());
        row.setPayload(payload);
        row.setStatus(OutboxStatus.PENDING);
        row.setRetryCount(0);
        mapper.insert(row);
        return row.getId();
    }

    /** 投递成功（独立事务：AFTER_COMMIT 之后原事务已结束）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(Long id) {
        if (id == null) {
            return;
        }
        mapper.markDone(id);
    }

    /**
     * 投递失败：未超限回到 {@code pending} 等待重放，超限置 {@code dead}。
     *
     * <p><b>超限判定依赖 {@code row.retryCount}，因此两条调用路径的能力不同</b>：
     * <ul>
     *   <li>{@code OutboxDispatcher} 传入的是从库里领取的完整行 → 能正确判定并置 {@code dead}；</li>
     *   <li>{@code DomainEventPublisher} 同步分发失败时只有一个 id（没有读行），
     *       {@code retryCount} 为空按 0 起算 → <b>只会置 pending，不会置 dead</b>。</li>
     * </ul>
     * 这是有意的：死亡判决统一交给投递器（它读得到真实 {@code retry_count}），
     * 同步路径只负责「留痕并等待重放」。计数本身由 SQL {@code retry_count = retry_count + 1}
     * 保证不丢，故同步路径的判定偏差不会导致漏计或死循环。
     *
     * @return 是否为超限置 dead（供调用方告警）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(OutboxEvent row, Throwable error) {
        int attempted = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
        boolean dead = attempted >= maxRetry;
        mapper.markRetried(row.getId(),
                dead ? OutboxStatus.DEAD : OutboxStatus.PENDING,
                backoffSeconds,
                describe(error));
        return dead;
    }

    private String describe(Throwable error) {
        if (error == null) {
            return null;
        }
        String msg = error.getMessage();
        String text = error.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
        // error 列为 TEXT，但仍限制长度：避免堆栈/大对象把日志与表撑爆
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
