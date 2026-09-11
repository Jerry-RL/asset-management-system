package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 预警触发（DSD §4.8）：AlertTriggered → 预警处置待办、通知。
 *
 * <p>由 {@code AlertService} 在生成预警记录后发布。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlertTriggeredEvent extends DomainEvent {

    private final Long alertId;
    private final String alertType;
    private final String subType;
    private final int level;
    private final String bizType;
    private final Long bizId;
    private final String title;

    @JsonCreator
    public AlertTriggeredEvent(
            @JsonProperty("alertId") Long alertId,
            @JsonProperty("alertType") String alertType,
            @JsonProperty("subType") String subType,
            @JsonProperty("level") int level,
            @JsonProperty("bizType") String bizType,
            @JsonProperty("bizId") Long bizId,
            @JsonProperty("title") String title) {
        this.alertId = alertId;
        this.alertType = alertType;
        this.subType = subType;
        this.level = level;
        this.bizType = bizType;
        this.bizId = bizId;
        this.title = title;
    }

    @Override
    public String aggregateType() {
        return "alert";
    }

    @Override
    public Long aggregateId() {
        return alertId;
    }
}
