package com.ams.platform.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 资产调拨完成（DSD §4.8）：AssetTransferred → 双方对账留痕、数据范围迁移通知。
 *
 * <p>由 {@code TransferService.approve} 在调拨单置完成后发布。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssetTransferredEvent extends DomainEvent {

    private final Long transferId;
    private final Long assetId;
    private final Long fromCompanyId;
    private final Long toCompanyId;

    @JsonCreator
    public AssetTransferredEvent(
            @JsonProperty("transferId") Long transferId,
            @JsonProperty("assetId") Long assetId,
            @JsonProperty("fromCompanyId") Long fromCompanyId,
            @JsonProperty("toCompanyId") Long toCompanyId) {
        this.transferId = transferId;
        this.assetId = assetId;
        this.fromCompanyId = fromCompanyId;
        this.toCompanyId = toCompanyId;
    }

    @Override
    public String aggregateType() {
        return "asset_transfer";
    }

    @Override
    public Long aggregateId() {
        return transferId;
    }
}
