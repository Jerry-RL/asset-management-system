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

    /**
     * 幂等推进：资产已处于终态 {@code exited} 时**跳过**写入并返回 false；其余非法迁移照旧抛 409。
     *
     * <h2>为什么必须有这条豁免</h2>
     * {@code exited} 是 {@link LeaseControlStatus} 的终态，且该状态机**不允许自迁移**
     * （{@code from.equals(to)} 直接判非法）、也不允许从终态迁出。而「已退出」的资产
     * 仍然需要登记新的处置单据：
     * <ul>
     *   <li><b>分次处置</b>：一次处置完成后再次登记处置（先退出部分面积、后处置剩余）；</li>
     *   <li><b>级联先退出</b>：项目 / 分区处置（V57）会一次性把其下资产置为 {@code exited}，
     *       之后为这些资产补登记资产级处置单时，租控写入**已无意义**（资产本就是终态）。</li>
     * </ul>
     * 若照旧调 {@link #transition}，会得到「租控状态不允许从 exited 迁移到 X」的 409，
     * 让「已处置的资产不能再登记处置」这条**从未被设计过**的规则悄悄生效 ——
     * 而它的根因只是状态机把「重复写入同一终态」也判成了非法。
     *
     * <h2>豁免范围严格限定</h2>
     * 只豁免「资产当前已是 {@code exited}」这一种情况。其余非法迁移（如
     * {@code leased → disposing}）仍然抛错 —— 那才是「在租资产须先退租才能处置」这条**真实**规则，
     * 不能一并放宽。
     *
     * @return 是否真的写了租控状态；{@code false} 表示因终态豁免而跳过（不留租控日志行，
     *         因为没有发生状态变化）
     */
    @Transactional
    public boolean transitionUnlessExited(Long assetId, String targetStatus, String bizType,
            Long bizId, String remark) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        if (LeaseControlStatus.EXITED.equals(asset.getLeaseControlStatus())) {
            return false;
        }
        transition(assetId, targetStatus, bizType, bizId, remark);
        return true;
    }

    /** 断言资产未抵押（处置/划转前校验，FR-MORT-003）。建议改用 CertificateService.assertNotMortgaged。 */
    public void assertNotMortgaged(Long assetId, boolean mortgaged) {
        if (mortgaged) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "资产处于在押状态，须先解押");
        }
    }
}
