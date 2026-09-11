package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.Getter;

/**
 * 账单逾期（DSD §4.8）：BillOverdue → 催缴升级、通知、待办。
 *
 * <p>由 {@code DunningService} 在催缴等级升级时发布。
 * <b>仅在真正逾期时发布</b>——到期前 L1 提醒（pre_due_remind）不是逾期，不得发布本事件，
 * 否则下游会把「即将到期」当成「已逾期」统计。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BillOverdueEvent extends DomainEvent {

    private final Long billId;
    private final String billNo;
    private final Long contractId;
    private final BigDecimal amount;
    private final int dunningLevel;
    private final long overdueDays;

    @JsonCreator
    public BillOverdueEvent(
            @JsonProperty("billId") Long billId,
            @JsonProperty("billNo") String billNo,
            @JsonProperty("contractId") Long contractId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("dunningLevel") int dunningLevel,
            @JsonProperty("overdueDays") long overdueDays) {
        this.billId = billId;
        this.billNo = billNo;
        this.contractId = contractId;
        this.amount = amount;
        this.dunningLevel = dunningLevel;
        this.overdueDays = overdueDays;
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
