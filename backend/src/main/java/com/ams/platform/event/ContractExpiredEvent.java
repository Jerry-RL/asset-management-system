package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import lombok.Getter;

/**
 * 合同已到期（DSD §4.8）：ContractExpired → 需续签/已到期预警、挂账处理。
 *
 * <p>由 {@code ScheduledJobs.contractExpiryScan} 在把合同置为「已到期」时发布。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContractExpiredEvent extends DomainEvent {

    private final Long contractId;
    private final String contractNo;
    private final LocalDate endDate;

    @JsonCreator
    public ContractExpiredEvent(
            @JsonProperty("contractId") Long contractId,
            @JsonProperty("contractNo") String contractNo,
            @JsonProperty("endDate") LocalDate endDate) {
        this.contractId = contractId;
        this.contractNo = contractNo;
        this.endDate = endDate;
    }

    @Override
    public String aggregateType() {
        return "contract";
    }

    @Override
    public Long aggregateId() {
        return contractId;
    }
}
