package com.ams.platform.event;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;

/**
 * 领域事件基类（DSD §4.8）：afterCommit 发布，避免回滚后误通知。
 */
@Getter
public abstract class DomainEvent {

    private final String eventId;
    private final LocalDateTime occurredAt;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = LocalDateTime.now();
    }
}
