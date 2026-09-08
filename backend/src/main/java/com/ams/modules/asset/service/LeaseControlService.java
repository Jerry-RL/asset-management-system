package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.LeaseControlLog;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.LeaseControlLogMapper;
import com.ams.platform.security.SecurityUtils;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租控状态机统一入口（DSD §4.3）：transition(assetId, event, operator)。
 * 禁止 Repository 直改；乐观锁 version。
 */
@Service
public class LeaseControlService {

    private final AssetMapper assetMapper;
    private final LeaseControlLogMapper logMapper;

    public LeaseControlService(AssetMapper assetMapper, LeaseControlLogMapper logMapper) {
        this.assetMapper = assetMapper;
        this.logMapper = logMapper;
    }

    @Transactional
    public void transition(Long assetId, String targetStatus, String bizType, Long bizId, String remark) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        String from = asset.getLeaseControlStatus();
        if (!LeaseControlStatus.canTransition(from, targetStatus)) {
            throw new AppException(ErrorCode.CONFLICT,
                    "租控状态不允许从 " + from + " 迁移到 " + targetStatus);
        }
        asset.setLeaseControlStatus(targetStatus);
        int updated = assetMapper.updateById(asset); // 乐观锁
        if (updated == 0) {
            throw new AppException(ErrorCode.CONFLICT, "租控状态已被并发修改，请刷新重试");
        }

        LeaseControlLog log = new LeaseControlLog();
        log.setAssetId(assetId);
        log.setFromStatus(from);
        log.setToStatus(targetStatus);
        log.setBizType(bizType);
        log.setBizId(bizId);
        log.setOperatorId(SecurityUtils.currentUserIdOrNull());
        log.setRemark(remark);
        log.setCreatedAt(LocalDateTime.now());
        logMapper.insert(log);
    }

    /** 断言资产处于空置（占用/自用/处置前校验）。 */
    public void assertVacant(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        if (!LeaseControlStatus.VACANT.equals(asset.getLeaseControlStatus())) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "仅空置资产可执行此操作");
        }
    }

    /** 断言资产未抵押（处置/划转前校验，FR-MORT-003）。 */
    public void assertNotMortgaged(Long assetId, boolean mortgaged) {
        if (mortgaged) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "资产处于在押状态，须先解押");
        }
    }
}
