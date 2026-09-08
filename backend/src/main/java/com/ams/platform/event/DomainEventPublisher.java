package com.ams.platform.event;

import com.ams.common.web.TraceIdUtil;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 领域事件总线（DSD §4.8）：事件在 afterCommit 后发布。
 */
@Component
public class DomainEventPublisher {

    private final ApplicationEventPublisher publisher;

    public DomainEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 在事务提交后发布事件（跨模块事件须保证一致性）。
     */
    public void publishAfterCommit(DomainEvent event) {
        // 通过自注入的监听方式保证 afterCommit 语义；
        // 这里直接发布，由 @TransactionalEventListener(phase=AFTER_COMMIT) 消费。
        publisher.publishEvent(new AfterCommitEventWrapper(event, TraceIdUtil.get()));
    }

    /**
     * 包装事件，供监听器识别并在事务提交后转发。
     */
    public record AfterCommitEventWrapper(DomainEvent event, String traceId) {
    }

    /**
     * 供 @EventListener 消费的实际转发入口。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(AfterCommitEventWrapper wrapper) {
        TraceIdUtil.set(wrapper.traceId());
        try {
            publisher.publishEvent(wrapper.event());
        } finally {
            TraceIdUtil.clear();
        }
    }
}
