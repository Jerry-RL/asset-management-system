package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;

/**
 * 领域事件基类（DSD §4.8）：afterCommit 发布，避免回滚后误通知。
 *
 * <p><b>身份与载荷分离</b>（ADR-0020 决策 F）：{@code eventId} / {@code occurredAt} 是事件的
 * <b>身份</b>，由 {@code domain_event_outbox} 的列承载，并 {@link JsonIgnore} 排除在 payload 之外；
 * 调度重放时通过 {@link #restoreIdentity} 还原，保证<b>重放前后 eventId 不变</b>——
 * 这是下游按 {@code (eventId, consumer)} 幂等去重的前提。
 *
 * <p>若把身份也放进 payload，「事件 ID」就会有两个来源（列与载荷）；一旦不一致，
 * 幂等台账会因为换了 ID 而失去作用。
 *
 * <p>子类约定：构造器须标注 {@code @JsonCreator} 与 {@code @JsonProperty}，
 * 否则 outbox 重放无法反序列化（普通类不会被 Jackson 推断为隐式 creator）；
 * 并覆写 {@link #aggregateType()} / {@link #aggregateId()} 供排障反查。
 */
@Getter
public abstract class DomainEvent {

    /** 事件身份；由 outbox 列承载，不进入 payload。 */
    @JsonIgnore
    private String eventId;

    /** 发生时间；由 outbox 列承载，不进入 payload。 */
    @JsonIgnore
    private LocalDateTime occurredAt;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = LocalDateTime.now();
    }

    /**
     * 还原事件身份（<b>仅供 outbox 重放使用</b>）。
     *
     * <p>业务代码不得调用：事件身份一旦生成即不可变，重放必须复用原 ID 而非新生成。
     */
    public void restoreIdentity(String eventId, LocalDateTime occurredAt) {
        this.eventId = eventId;
        this.occurredAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
    }

    /** 聚合类型（排障与按业务反查用）；默认空，子类覆写。不进入 payload。 */
    @JsonIgnore
    public String aggregateType() {
        return null;
    }

    /** 聚合 ID；默认空，子类覆写。不进入 payload。 */
    @JsonIgnore
    public Long aggregateId() {
        return null;
    }
}
