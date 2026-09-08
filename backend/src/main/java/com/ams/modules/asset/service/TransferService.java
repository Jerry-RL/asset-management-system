package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetTransfer;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetTransferMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资产调拨（FR-CERT-002）：跨公司调拨、带租迁移、双方留痕。
 */
@Service
public class TransferService {

    private final AssetTransferMapper transferMapper;
    private final AssetMapper assetMapper;
    private final LeaseControlService leaseControlService;

    public TransferService(
            AssetTransferMapper transferMapper,
            AssetMapper assetMapper,
            LeaseControlService leaseControlService) {
        this.transferMapper = transferMapper;
        this.assetMapper = assetMapper;
        this.leaseControlService = leaseControlService;
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

    /**
     * 调拨审批通过后执行迁移（DSD §4.16）：
     * - 空置资产：直接迁移经营公司。
     * - 在租资产：仅允许「带租调拨」（合同随迁），否则阻断。
     */
    @Transactional
    public AssetTransfer approve(Long id) {
        AssetTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        Asset asset = assetMapper.selectById(transfer.getAssetId());
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        String status = asset.getLeaseControlStatus();
        if ("leased".equals(status) || "partial_leased".equals(status)) {
            if (!"with_contract".equals(transfer.getTransferType())) {
                throw new AppException(ErrorCode.BUSINESS_ERROR,
                        "在租资产须选择「带租调拨」，或先退租/解除占用");
            }
            // 带租调拨：经营公司随合同迁移（合同/欠费/保证金/预收迁移由 contract 域处理）
        }
        // 迁移经营公司
        asset.setOperatingCompanyId(transfer.getToCompanyId());
        assetMapper.updateById(asset);

        transfer.setStatus("completed");
        transferMapper.updateById(transfer);
        return transfer;
    }
}
