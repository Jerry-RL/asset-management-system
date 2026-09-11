package com.ams.platform.event;

import com.ams.common.web.TraceIdUtil;
import com.ams.platform.event.outbox.OutboxStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 领域事件总线（DSD §4.8 + ADR-0020 决策 F）：事件在 afterCommit 后发布，并写入发件箱。
 *
 * <p>三段式投递：
 * <ol>
 *   <li><b>同事务写发件箱</b>——业务回滚则事件行一并回滚（不该发的不能发）；</li>
 *   <li><b>提交后同步分发</b>——低延迟；成功即置 {@code done}；</li>
 *   <li><b>兜底重放</b>——同步分发失败或进程中断遗留的 {@code pending} 由
 *       {@code OutboxDispatcher} 重投。</li>
 * </ol>
 *
 * <p><b>两个刻意的行为选择</b>：
 * <ul>
 *   <li>{@code fallbackExecution = true}：原实现（默认 false）在「无事务时」<b>静默丢弃</b>事件
 *       —— {@code @TransactionalEventListener} 在没有活动事务时根本不会被调用。
 *       定时任务、非事务调用点因此丢失事件也无人知晓。开启后无事务时立即分发，
 *       且发件箱已落行，即便分发失败也能重放。</li>
 *   <li>监听器异常<b>原样上抛</b>：保持既有语义不变（此前异常会冒泡到调用方），
 *       同时把事件留在 {@code pending} 等待重放——二者不冲突。</li>
 * </ul>
 */
@Component
public class DomainEventPublisher {

    private final ApplicationEventPublisher publisher;
    private final OutboxStore outboxStore;
    private final ObjectMapper objectMapper;

    public DomainEventPublisher(
            ApplicationEventPublisher publisher,
            OutboxStore outboxStore,
            ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.outboxStore = outboxStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录并发布事件：先写发件箱（同事务），提交后再分发。
     *
     * @throws IllegalStateException 事件无法序列化（编程错误，须在开发期暴露而非静默丢事件）
     */
    public void publishAfterCommit(DomainEvent event) {
        Long outboxId = outboxStore.record(event, serialize(event));
        publisher.publishEvent(new AfterCommitEventWrapper(event, outboxId, TraceIdUtil.get()));
    }

    /**
     * 供 {@code AFTER_COMMIT}（或未开启事务时的立即执行）消费的转发入口。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommitted(AfterCommitEventWrapper wrapper) {
        TraceIdUtil.set(wrapper.traceId());
        try {
            publisher.publishEvent(wrapper.event());
            outboxStore.markDone(wrapper.outboxId());
        } catch (RuntimeException ex) {
            // 分发失败：留在发件箱等待重放（markRetry 会累加重试次数，超限置 dead）
            outboxStore.markRetry(newFailureRow(wrapper.outboxId()), ex);
            throw ex;
        } finally {
            TraceIdUtil.clear();
        }
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "领域事件序列化失败: " + event.getClass().getSimpleName()
                            + "（事件类须有无参/带 @JsonCreator 的构造器）", ex);
        }
    }

    /**
     * 构造仅含 {@code id} 的重试入参。
     *
     * <p>同步分发失败时手上只有 id，没有从库里读回的行，因此 {@code retryCount} 为空。
     * {@link OutboxStore#markRetry} 对此的处理是<b>只会置 pending、不会置 dead</b>——
     * 死亡判决统一由 {@code OutboxDispatcher}（读得到真实 {@code retry_count}）负责。
     * 计数本身由 SQL 自增保证不丢，故这里不额外查库。
     */
    private com.ams.platform.event.outbox.OutboxEvent newFailureRow(Long outboxId) {
        com.ams.platform.event.outbox.OutboxEvent row =
                new com.ams.platform.event.outbox.OutboxEvent();
        row.setId(outboxId);
        return row;
    }

    /**
     * 包装事件，附带发件箱行 ID 与 traceId，供监听器识别并在事务提交后转发。
     */
    public record AfterCommitEventWrapper(DomainEvent event, Long outboxId, String traceId) {
    }
}
