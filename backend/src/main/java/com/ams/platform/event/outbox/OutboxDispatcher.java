package com.ams.platform.event.outbox;

import com.ams.platform.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发件箱兜底投递器（ADR-0020 决策 F、设计 §12.4）。
 *
 * <p>职责：把 {@code pending} 的事件重新投递给 {@code @EventListener} 消费者。
 * 正常路径（{@code AFTER_COMMIT} 同步分发）走的是 {@code DomainEventPublisher}，
 * 本类只处理「同步分发失败或进程中断」遗留的事件，因此<b>重放是常态而非异常</b>——
 * 这也是下游必须按 {@code (eventId, consumer)} 幂等的原因。
 *
 * <p>投递失败会累加重试次数；超上限置 {@code dead} 并打 ERROR 日志，需运维介入
 * （设计 §14 验收 SQL ⑤ 要求 {@code dead = 0}）。
 *
 * <p>本类不开启事务：领取语句自带租约，投递本身由监听器各自的事务边界决定。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxEventMapper mapper;
    private final OutboxStore store;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int leaseSeconds;

    public OutboxDispatcher(
            OutboxEventMapper mapper,
            OutboxStore store,
            ApplicationEventPublisher publisher,
            ObjectMapper objectMapper,
            @Value("${ams.event.outbox.batch-size:100}") int batchSize,
            @Value("${ams.event.outbox.lease-seconds:120}") int leaseSeconds) {
        this.mapper = mapper;
        this.store = store;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * 定时兜底投递。生产默认开启（{@code ams.scheduling.enabled}），
     * 测试环境关闭定时后可直接调 {@link #dispatchOnce()} 断言行为。
     */
    @Scheduled(fixedDelayString = "${ams.event.outbox.dispatch-interval-ms:30000}",
            initialDelayString = "${ams.event.outbox.initial-delay-ms:60000}")
    public void dispatch() {
        int delivered = dispatchOnce();
        if (delivered > 0) {
            log.info("outbox dispatched {} events", delivered);
        }
    }

    /**
     * 单次投递，返回成功条数。
     *
     * @return 成功投递的事件数
     */
    public int dispatchOnce() {
        List<OutboxEvent> claimed = mapper.claimPending(batchSize, leaseSeconds);
        int delivered = 0;
        for (OutboxEvent row : claimed) {
            if (deliver(row)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * 投递单条事件。
     *
     * <p>失败时不向外抛：调度循环必须继续处理其余事件，单条坏事件不应拖垮整批。
     */
    private boolean deliver(OutboxEvent row) {
        try {
            DomainEvent event = EventTypeRegistry.deserialize(row, objectMapper);
            publisher.publishEvent(event);
            store.markDone(row.getId());
            return true;
        } catch (Exception ex) {
            boolean dead = store.markRetry(row, ex);
            if (dead) {
                log.error("outbox event dead after retries: eventId={}, type={}, cause={}",
                        row.getEventId(), row.getEventType(), ex.getMessage(), ex);
            } else {
                log.warn("outbox redelivery failed, will retry: eventId={}, type={}, cause={}",
                        row.getEventId(), row.getEventType(), ex.getMessage());
            }
            return false;
        }
    }
}
