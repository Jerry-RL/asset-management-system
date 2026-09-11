package com.ams.platform.event.outbox;

/**
 * 发件箱状态（{@code domain_event_outbox.status}）。
 */
public final class OutboxStatus {

    private OutboxStatus() {
    }

    /** 待投递（含等待重试）。 */
    public static final String PENDING = "pending";
    /** 已投递。 */
    public static final String DONE = "done";
    /** 重试超限，需人工处置；非 0 即告警（设计 §14 验收 SQL ⑤）。 */
    public static final String DEAD = "dead";
}
