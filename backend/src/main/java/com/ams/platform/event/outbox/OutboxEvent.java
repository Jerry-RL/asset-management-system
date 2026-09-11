package com.ams.platform.event.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 领域事件发件箱（{@code domain_event_outbox}，ADR-0020 决策 F）。
 *
 * <p>不使用 {@code BaseEntity}：本表由平台基础设施写入，审计字段对排障无增益，
 * 且 {@code created_at} 已由 DDL 默认值保证（避免依赖 MetaObjectHandler 的登录上下文）。
 */
@Data
@TableName("domain_event_outbox")
public class OutboxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件唯一标识；重放保持稳定，是消费幂等依据。 */
    private String eventId;

    /** 事件类型（领域事件类简单名）。 */
    private String eventType;

    private String aggregateType;
    private Long aggregateId;

    /** 事件载荷（仅领域字段，不含 eventId/occurredAt）。 */
    private String payload;

    /** 见 {@link OutboxStatus}。 */
    private String status;

    private Integer retryCount;

    /** 同时充当「重试时刻」与「领取租约」。 */
    private LocalDateTime nextRetryAt;

    private String error;
    private LocalDateTime createdAt;
    private LocalDateTime dispatchedAt;
}
