package com.ams.platform.event;

import lombok.Getter;

/**
 * 收款登记事件（DSD §4.8）：PaymentRegistered → 工作端待确认款入账等。
 */
@Getter
public class PaymentRegisteredEvent extends DomainEvent {

    private final Long paymentId;

    public PaymentRegisteredEvent(Long paymentId) {
        this.paymentId = paymentId;
    }
}
