package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 资产处置完成（DSD §4.8）：DisposalCompleted → 备案提醒、租控归位。
 *
 * <p>由 {@code DisposalService.complete} 在处置单置完成后发布。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DisposalCompletedEvent extends DomainEvent {

    private final Long disposalId;
    private final Long assetId;
    private final String disposalType;

    @JsonCreator
    public DisposalCompletedEvent(
            @JsonProperty("disposalId") Long disposalId,
            @JsonProperty("assetId") Long assetId,
            @JsonProperty("disposalType") String disposalType) {
        this.disposalId = disposalId;
        this.assetId = assetId;
        this.disposalType = disposalType;
    }

    @Override
    public String aggregateType() {
        return "disposal_order";
    }

    @Override
    public Long aggregateId() {
        return disposalId;
    }
}
