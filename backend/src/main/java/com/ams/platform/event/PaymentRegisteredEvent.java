package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 收款登记事件（DSD §4.8）：PaymentRegistered → 工作端待确认款入账等。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentRegisteredEvent extends DomainEvent {

    private final Long paymentId;

    @JsonCreator
    public PaymentRegisteredEvent(@JsonProperty("paymentId") Long paymentId) {
        this.paymentId = paymentId;
    }

    @Override
    public String aggregateType() {
        return "payment";
    }

    @Override
    public Long aggregateId() {
        return paymentId;
    }
}
