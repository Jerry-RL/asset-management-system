package com.ams.modules.asset.service;

import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.dto.LeaseStatusSnapshot;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetOccupancyMapper;
import com.ams.modules.asset.mapper.AssetUnitMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租控状态派生器：把「占用事实」物化为 {@code asset.lease_control_status} 与
 * {@code asset_unit.unit_status}（ADR-0019 决策 B1）。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>真源是 SQL 视图</b>（{@code v_unit_lease_status} / {@code v_asset_lease_status_derived}），
 *       本类只做投影与回写。派生逻辑不复制到 Java，避免两套规则漂移。</li>
 *   <li><b>唯一写入口</b>：任何业务路径都不再直改租控状态；
 *       {@code LeaseControlStatus.canTransition} 作废，合法性由占用区间互斥约束保证。</li>
 *   <li><b>幂等</b>：全部比较使用 {@code Objects.equals} / {@code IS DISTINCT FROM}，
 *       无变化不产生 UPDATE（避免乐观锁版本无意义推进与审计噪声）。</li>
 * </ol>
 */
@Service
public class LeaseStatusDeriver {

    private final AssetMapper assetMapper;
    private final AssetUnitMapper unitMapper;
    private final AssetOccupancyMapper occupancyMapper;

    public LeaseStatusDeriver(
            AssetMapper assetMapper,
            AssetUnitMapper unitMapper,
            AssetOccupancyMapper occupancyMapper) {
        this.assetMapper = assetMapper;
        this.unitMapper = unitMapper;
        this.occupancyMapper = occupancyMapper;
    }

    /**
     * 刷新单个资产的单元状态与资产租控状态。
     *
     * @return 是否产生了实际变更（便于调用方判断是否需要发领域事件）
     */
    @Transactional
    public boolean refresh(Long assetId) {
        if (assetId == null) {
            return false;
        }
        // 1) 单元状态物化：单语句集合更新，差异行才写
        unitMapper.syncUnitStatus(assetId);

        // 2) 资产状态物化：视图为真源
        LeaseStatusSnapshot snap = occupancyMapper.selectDerived(assetId);
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            return false;
        }
        String derived = (snap == null || snap.getDerivedStatus() == null)
                ? LeaseControlStatus.VACANT
                : snap.getDerivedStatus();

        boolean changed = false;
        if (!Objects.equals(asset.getLeaseControlStatus(), derived)) {
            asset.setLeaseControlStatus(derived);
            changed = true;
        }
        if (snap != null && !Objects.equals(asset.getOccupancyRatio(), snap.getOccupancyRatio())) {
            asset.setOccupancyRatio(snap.getOccupancyRatio());
            changed = true;
        }

        // 3) 空置时长派生：'已退出' 不属于空置，不参与盘活督办
        boolean free = LeaseControlStatus.VACANT.equals(derived)
                || LeaseControlStatus.LEASING.equals(derived);
        if (free) {
            if (asset.getVacantSince() == null) {
                LocalDate lastReleased = occupancyMapper.selectLastReleasedDate(assetId);
                asset.setVacantSince(lastReleased == null
                        ? LocalDateTime.now()
                        : lastReleased.atStartOfDay());
                changed = true;
            }
        } else if (asset.getVacantSince() != null || asset.getVacantReason() != null) {
            asset.setVacantSince(null);
            asset.setVacantReason(null);
            changed = true;
        }

        if (changed) {
            // Asset.version 为乐观锁字段：失败说明并发写入，交由上层重试/提示刷新
            assetMapper.updateById(asset);
        }
        return changed;
    }

    /**
     * 收敛作业：只修复「物化列 ≠ 视图」的资产，不扫描全表。
     *
     * <p>用于双写过渡期的一致性兜底（定时任务），以及迁移后的一次性对齐。
     *
     * @param limit 单批上限，避免长事务锁表
     * @return 实际发生变更的资产数
     */
    @Transactional
    public int refreshReconciled(int limit) {
        int capped = Math.max(1, Math.min(limit, 1000));
        List<LeaseStatusSnapshot> diffs = occupancyMapper.selectReconcile(capped);
        int n = 0;
        for (LeaseStatusSnapshot diff : diffs) {
            if (refresh(diff.getAssetId())) {
                n++;
            }
        }
        return n;
    }
}
