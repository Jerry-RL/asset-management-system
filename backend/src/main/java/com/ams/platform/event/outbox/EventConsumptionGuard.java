package com.ams.platform.event.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事件消费幂等守卫：确保同一 {@code (eventId, consumer)} 只产生一次副作用。
 *
 * <p>存在的理由：outbox 在「投递成功但落 {@code done} 失败」时会重放，
 * 重放必然导致重复投递，因此<b>下游必须幂等</b>（ADR-0020 决策 F）。
 *
 * <p><b>为什么用 {@code REQUIRED} 而不是 {@code REQUIRES_NEW}</b>：
 * 台账必须与消费效果<b>同事务</b>提交。若用独立事务，会出现「台账已记已消费、
 * 业务效果却因异常回滚」——此时重试被误判为重复而跳过，通知永久丢失。
 * 用 {@code REQUIRED} 后两者同生共死：失败一起回滚，重试可安全重入。
 *
 * <p>因此调用方<b>必须处于事务中</b>。这一约束由 {@link #tryConsume} 主动校验并抛错，
 * 而不是靠注释提醒——静默地退化为「非原子」是最坏结果。
 */
@Service
public class EventConsumptionGuard {

    private final EventConsumptionMapper mapper;

    public EventConsumptionGuard(EventConsumptionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 尝试登记消费。
     *
     * @param eventId  事件 ID（重放前后稳定）
     * @param consumer 消费者标识（如 {@code notification}）
     * @return {@code true} 首次消费，可继续；{@code false} 已消费过，应跳过
     * @throws IllegalStateException 调用方未开启事务（会导致台账与业务效果非原子）
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean tryConsume(String eventId, String consumer) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "事件消费幂等守卫必须在事务内调用：否则幂等台账与业务效果不同事务，"
                            + "失败重试会被误判为已消费。请在监听器方法上标注 @Transactional。");
        }
        if (eventId == null) {
            // 无身份的事件无法幂等：宁可放行并留痕，也不要静默丢弃业务通知
            return true;
        }
        return mapper.tryInsert(eventId, consumer) > 0;
    }
}
