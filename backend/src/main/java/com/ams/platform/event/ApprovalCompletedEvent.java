package com.ams.platform.event;

import lombok.Getter;

/**
 * 审批完成事件（DSD §4.8）：ApprovalCompleted → 触发缴费计划生成、通知等。
 */
@Getter
public class ApprovalCompletedEvent extends DomainEvent {

    private final String bizType;
    private final Long bizId;
    private final boolean approved;

    public ApprovalCompletedEvent(String bizType, Long bizId, boolean approved) {
        this.bizType = bizType;
        this.bizId = bizId;
        this.approved = approved;
    }
}
