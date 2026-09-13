package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetTransfer;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetTransferMapper;
import com.ams.platform.event.AssetTransferredEvent;
import com.ams.platform.event.DomainEventPublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产调拨（FR-CERT-002）：跨公司调拨、带租迁移、交接清单、双方留痕。
 */
@Service
public class TransferService {

    private final AssetTransferMapper transferMapper;
    private final AssetMapper assetMapper;
    private final CertificateService certificateService;
    private final AssetHandoverBuilder handoverBuilder;
    private final ObjectMapper objectMapper;
    private final DomainEventPublisher eventPublisher;

    public TransferService(
            AssetTransferMapper transferMapper,
            AssetMapper assetMapper,
            CertificateService certificateService,
            AssetHandoverBuilder handoverBuilder,
            ObjectMapper objectMapper,
            DomainEventPublisher eventPublisher) {
        this.transferMapper = transferMapper;
        this.assetMapper = assetMapper;
        this.certificateService = certificateService;
        this.handoverBuilder = handoverBuilder;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public AssetTransfer create(AssetTransfer transfer) {
        transfer.setStatus("draft");
        transferMapper.insert(transfer);
        return transfer;
    }

    public List<AssetTransfer> list(Long assetId) {
        return transferMapper.selectList(
                new LambdaQueryWrapper<AssetTransfer>()
                        .eq(assetId != null, AssetTransfer::getAssetId, assetId)
                        .orderByDesc(AssetTransfer::getId));
    }

    public AssetTransfer get(Long id) {
        AssetTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return transfer;
    }

    /**
     * 调拨审批通过后执行迁移（DSD §4.16）：
     * - 空置资产：直接迁移经营公司。
     * - 在租资产：仅允许「带租调拨」；生成交接清单（合同/欠费/保证金/预收）。
     */
    @Transactional
    public AssetTransfer approve(Long id) {
        AssetTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        if ("completed".equals(transfer.getStatus())) {
            return transfer; // 幂等
        }
        Asset asset = assetMapper.selectById(transfer.getAssetId());
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        certificateService.assertNotMortgaged(transfer.getAssetId());
        String status = asset.getLeaseControlStatus();
        boolean leased = "leased".equals(status) || "partial_leased".equals(status);
        if (leased && !"with_contract".equals(transfer.getTransferType())) {
            throw new AppException(ErrorCode.BUSINESS_ERROR,
                    "在租资产须选择「带租调拨」，或先退租/解除占用");
        }

        Map<String, Object> handover = handoverBuilder.build(
                asset,
                transfer.getFromCompanyId() != null
                        ? transfer.getFromCompanyId() : asset.getOperatingCompanyId(),
                transfer.getToCompanyId(),
                Map.of("transferType", transfer.getTransferType()));
        try {
            transfer.setHandoverJson(objectMapper.writeValueAsString(handover));
        } catch (Exception ex) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "生成交接清单失败");
        }

        // 迁移经营公司（合同随资产经营主体，欠费/保证金/预收留在合同上随迁）
        Long from = asset.getOperatingCompanyId();
        if (transfer.getFromCompanyId() == null) {
            transfer.setFromCompanyId(from);
        }
        asset.setOperatingCompanyId(transfer.getToCompanyId());
        assetMapper.updateById(asset);

        transfer.setStatus("completed");
        transferMapper.updateById(transfer);
        // DSD §4.8：调拨完成 → AssetTransferred（双方对账留痕、数据范围迁移、下游投影刷新）
        eventPublisher.publishAfterCommit(new AssetTransferredEvent(
                transfer.getId(), asset.getId(),
                transfer.getFromCompanyId(), transfer.getToCompanyId()));
        return transfer;
    }
}
