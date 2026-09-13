package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 资产权属流转完成：{@code OwnershipTransferred → 双方对账提醒}。
 *
 * <p>由 {@code OwnershipTransferService.effect} 在逐个资产改完公司字段后**按资产**发布
 * （而不是一张单一条）—— 通知与下游投影都按资产粒度消费，一张单一条会让下游还得自己拆。
 *
 * <p>与 {@link AssetTransferredEvent}（调拨，单资产、改经营公司）刻意分开：两者的业务口径
 * 与通知文案都不同，合并一个事件会让监听器必须靠额外字段去猜是哪一种。
 *
 * <p>全部字段 final + {@code @JsonCreator}：outbox 重放要求载荷能反序列化，
 * 缺 {@code @JsonCreator} 会让重放失败并把事件置 dead（见 {@code EventTypeRegistryTest}）。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OwnershipTransferredEvent extends DomainEvent {

    private final Long transferId;
    private final Long assetId;
    private final Long fromCompanyId;
    private final Long toCompanyId;
    /** internal / external —— 外部流转需要双方对账关注「已转出集团」。 */
    private final String direction;

    @JsonCreator
    public OwnershipTransferredEvent(
            @JsonProperty("transferId") Long transferId,
            @JsonProperty("assetId") Long assetId,
            @JsonProperty("fromCompanyId") Long fromCompanyId,
            @JsonProperty("toCompanyId") Long toCompanyId,
            @JsonProperty("direction") String direction) {
        this.transferId = transferId;
        this.assetId = assetId;
        this.fromCompanyId = fromCompanyId;
        this.toCompanyId = toCompanyId;
        this.direction = direction;
    }

    @Override
    public String aggregateType() {
        return "ownership_transfer";
    }

    @Override
    public Long aggregateId() {
        return transferId;
    }
}
