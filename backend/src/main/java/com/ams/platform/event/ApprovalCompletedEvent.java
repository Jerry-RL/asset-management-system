package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 审批完成事件（DSD §4.8）：ApprovalCompleted → 触发缴费计划生成、通知等。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApprovalCompletedEvent extends DomainEvent {

    private final String bizType;
    private final Long bizId;
    private final boolean approved;

    @JsonCreator
    public ApprovalCompletedEvent(
            @JsonProperty("bizType") String bizType,
            @JsonProperty("bizId") Long bizId,
            @JsonProperty("approved") boolean approved) {
        this.bizType = bizType;
        this.bizId = bizId;
        this.approved = approved;
    }

    @Override
    public String aggregateType() {
        return bizType;
    }

    @Override
    public Long aggregateId() {
        return bizId;
    }
}
