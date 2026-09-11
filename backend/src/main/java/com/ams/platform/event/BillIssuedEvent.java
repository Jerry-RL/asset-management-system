package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;

/**
 * 账单已出账（DSD §4.8）：BillIssued → 租户缴费提醒。
 *
 * <p>由 {@code BillService.issueBills} 在生成账单后发布。出账可能一次产生多笔，
 * 事件按账单逐条发布，便于下游按账单生成提醒与投影。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BillIssuedEvent extends DomainEvent {

    private final Long billId;
    private final String billNo;
    private final Long contractId;
    private final BigDecimal amount;
    private final LocalDate dueDate;

    @JsonCreator
    public BillIssuedEvent(
            @JsonProperty("billId") Long billId,
            @JsonProperty("billNo") String billNo,
            @JsonProperty("contractId") Long contractId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("dueDate") LocalDate dueDate) {
        this.billId = billId;
        this.billNo = billNo;
        this.contractId = contractId;
        this.amount = amount;
        this.dueDate = dueDate;
    }

    @Override
    public String aggregateType() {
        return "bill";
    }

    @Override
    public Long aggregateId() {
        return billId;
    }
}
