package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.AssetLeaseGroups;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.UnitNoGenerator;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetUnitMapper;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计租单元服务：维护「资产 → 单元」这一层的面积预算与拆分（ADR-0019 决策 A1）。
 *
 * <p>不变量（本类是唯一维护入口）：
 * <ul>
 *   <li><b>INV-1</b>：Σ 有效单元面积 ≤ 资产面积（{@code asset.lease_area} 优先，回退 {@code asset.area}）；</li>
 *   <li><b>INV-2</b>：每个资产至少一个有效单元（保证占用表的 {@code asset_unit_id} 可 NOT NULL）；</li>
 *   <li><b>INV-3</b>：存在生效占用的单元不可拆分（否则占用会失去标的）。</li>
 * </ul>
 */
@Service
public class AssetUnitService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");

    private final AssetUnitMapper unitMapper;
    private final AssetMapper assetMapper;
    private final AssetOccupancyService occupancyService;
    private final LeaseListingMapper listingMapper;
    private final LeaseStatusDeriver deriver;

    public AssetUnitService(
            AssetUnitMapper unitMapper,
            AssetMapper assetMapper,
            AssetOccupancyService occupancyService,
            LeaseListingMapper listingMapper,
            LeaseStatusDeriver deriver) {
        this.unitMapper = unitMapper;
        this.assetMapper = assetMapper;
        this.occupancyService = occupancyService;
        this.listingMapper = listingMapper;
        this.deriver = deriver;
    }

    /** 资产下的有效单元（按 sort、id 排序）。 */
    public List<AssetUnit> listByAsset(Long assetId) {
        return unitMapper.selectList(new LambdaQueryWrapper<AssetUnit>()
                .eq(AssetUnit::getAssetId, assetId)
                .isNull(AssetUnit::getDeletedAt)
                .orderByAsc(AssetUnit::getSort)
                .orderByAsc(AssetUnit::getId));
    }

    /** 选取第一个「可租」单元（招租发布、新签合同用）。无则返回 {@code null}。 */
    public AssetUnit pickLeasable(Long assetId) {
        return unitMapper.selectOne(new LambdaQueryWrapper<AssetUnit>()
                .eq(AssetUnit::getAssetId, assetId)
                .in(AssetUnit::getUnitStatus, AssetLeaseGroups.IDLE)
                .isNull(AssetUnit::getDeletedAt)
                .orderByAsc(AssetUnit::getSort)
                .orderByAsc(AssetUnit::getId)
                .last("LIMIT 1"));
    }

    /**
     * 解析合同/招租的标的单元：显式传入则校验归属，未传则自动选取可租单元。
     *
     * <p>显式校验归属是必要的——避免接口层传入其他资产的单元 ID 造成跨资产错挂。
     */
    public AssetUnit resolveForLease(Long assetId, Long unitId) {
        if (assetId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产不能为空");
        }
        if (unitId != null) {
            AssetUnit unit = require(unitId);
            if (!assetId.equals(unit.getAssetId())) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "计租单元 " + unit.getUnitNo() + " 不属于该资产");
            }
            return unit;
        }
        AssetUnit picked = pickLeasable(assetId);
        if (picked == null) {
            throw new AppException(ErrorCode.CONFLICT,
                    "资产无可租单元，请先在资产详情中配置计租单元（assetId=" + assetId + "）");
        }
        return picked;
    }

    /**
     * 保证资产至少有一个单元（INV-2）。
     *
     * <p>必要场景：资产由 {@code ApplicationRunner} 演示数据/导入在 Flyway 之后创建，
     * 不会经过 V39 回填；此处做运行时补齐，使不变量在新数据上同样成立。
     */
    @Transactional
    public AssetUnit ensureUnitForAsset(Long assetId) {
        List<AssetUnit> existing = listByAsset(assetId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在: " + assetId);
        }
        AssetUnit unit = new AssetUnit();
        unit.setAssetId(assetId);
        unit.setUnitNo(UnitNoGenerator.of(asset.getAssetNo(), 1));
        unit.setUnitName("默认单元");
        unit.setArea(leasableAreaOf(asset));
        unit.setRentableArea(unit.getArea());
        unit.setUnitStatus(asset.getLeaseControlStatus() == null
                ? LeaseControlStatus.VACANT
                : asset.getLeaseControlStatus());
        unit.setFloorNo(asset.getFloorNo());
        unit.setSort(1);
        unit.setVersion(0);
        unit.setRemark("自动补齐：资产创建时未维护计租单元");
        unitMapper.insert(unit);
        deriver.refresh(assetId);
        return unit;
    }

    /** 新增单元（校验 INV-1：面积预算不得突破）。 */
    @Transactional
    public AssetUnit create(Long assetId, String unitName, BigDecimal area, Integer floorNo,
            String remark) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在: " + assetId);
        }
        if (area == null || area.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "单元面积必须大于 0");
        }
        BigDecimal budget = leasableAreaOf(asset);
        BigDecimal used = sumUnitArea(assetId);
        if (budget.compareTo(BigDecimal.ZERO) > 0
                && used.add(area).subtract(budget).compareTo(TOLERANCE) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "单元面积合计将超过资产可租面积（已用 " + used.toPlainString()
                            + "，可用 " + budget.toPlainString() + "）");
        }

        long count = unitMapper.selectCount(new LambdaQueryWrapper<AssetUnit>()
                .eq(AssetUnit::getAssetId, assetId)
                .isNull(AssetUnit::getDeletedAt));
        int seq = (int) count + 1;

        AssetUnit unit = new AssetUnit();
        unit.setAssetId(assetId);
        unit.setUnitNo(UnitNoGenerator.of(asset.getAssetNo(), seq));
        unit.setUnitName(unitName == null || unitName.isBlank() ? "单元" + seq : unitName);
        unit.setArea(area.setScale(2, RoundingMode.HALF_UP));
        unit.setRentableArea(unit.getArea());
        unit.setUnitStatus(LeaseControlStatus.VACANT);
        unit.setFloorNo(floorNo != null ? floorNo : asset.getFloorNo());
        unit.setSort(seq);
        unit.setVersion(0);
        unit.setRemark(remark);
        unitMapper.insert(unit);
        return unit;
    }

    /**
     * 拆分单元 —— 部分出租 / 部分占用的<b>唯一正确表达方式</b>（决策 A1）。
     *
     * <p>为什么不直接填 {@code area}：一旦允许「同一单元多个不重叠面积的占用」，
     * 互斥就从数据库约束退化为服务层纪律，而现状的超租缺陷正是服务层纪律失效的结果。
     *
     * <p>前置条件：无未收口占用、无生效招租发布。原单元软删除以保留历史。
     *
     * @param areas 子单元面积（至少 2 个，合计须等于原单元面积）
     */
    @Transactional
    public List<AssetUnit> split(Long unitId, List<BigDecimal> areas, String remark) {
        AssetUnit src = require(unitId);
        if (areas == null || areas.size() < 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "至少拆分为 2 个单元");
        }
        if (occupancyService.hasOpenOccupancy(unitId)) {
            throw new AppException(ErrorCode.CONFLICT, "单元存在生效占用，须先退租或解除占用后再拆分");
        }
        long activeListings = listingMapper.selectCount(new LambdaQueryWrapper<LeaseListing>()
                .eq(LeaseListing::getAssetUnitId, unitId)
                .eq(LeaseListing::getStatus, "active"));
        if (activeListings > 0) {
            throw new AppException(ErrorCode.CONFLICT, "单元存在生效招租发布，须先关闭后再拆分");
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal a : areas) {
            if (a == null || a.compareTo(BigDecimal.ZERO) <= 0) {
                throw new AppException(ErrorCode.BAD_REQUEST, "子单元面积必须大于 0");
            }
            sum = sum.add(a);
        }
        BigDecimal srcArea = src.getArea() == null ? BigDecimal.ZERO : src.getArea();
        if (srcArea.compareTo(BigDecimal.ZERO) > 0
                && sum.subtract(srcArea).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "子单元面积之和须等于原单元面积（原 " + srcArea.toPlainString()
                            + "，合计 " + sum.toPlainString() + "）");
        }

        List<AssetUnit> children = new ArrayList<>();
        int idx = 1;
        for (BigDecimal a : areas) {
            AssetUnit child = new AssetUnit();
            child.setAssetId(src.getAssetId());
            child.setUnitNo(UnitNoGenerator.childOf(src.getUnitNo(), idx));
            child.setUnitName((src.getUnitName() == null ? "单元" : src.getUnitName()) + "-" + idx);
            child.setArea(a.setScale(2, RoundingMode.HALF_UP));
            child.setRentableArea(child.getArea());
            child.setBaseRent(src.getBaseRent());
            child.setUnitStatus(LeaseControlStatus.VACANT);
            child.setFloorNo(src.getFloorNo());
            child.setSort(src.getSort() == null ? idx : src.getSort() * 100 + idx);
            child.setVersion(0);
            child.setRemark(remark);
            unitMapper.insert(child);
            children.add(child);
            idx++;
        }

        src.setDeletedAt(LocalDateTime.now());
        src.setRemark((src.getRemark() == null || src.getRemark().isBlank()
                ? ""
                : src.getRemark() + "；") + "已拆分为 " + areas.size() + " 个子单元");
        unitMapper.updateById(src);

        deriver.refresh(src.getAssetId());
        return children;
    }

    /** 资产可租面积预算：优先租赁面积，回退建筑面积。 */
    private BigDecimal leasableAreaOf(Asset asset) {
        if (asset.getLeaseArea() != null && asset.getLeaseArea().compareTo(BigDecimal.ZERO) > 0) {
            return asset.getLeaseArea();
        }
        return asset.getArea() == null ? BigDecimal.ZERO : asset.getArea();
    }

    private BigDecimal sumUnitArea(Long assetId) {
        return listByAsset(assetId).stream()
                .map(u -> u.getArea() == null ? BigDecimal.ZERO : u.getArea())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private AssetUnit require(Long unitId) {
        AssetUnit unit = unitMapper.selectById(unitId);
        if (unit == null || unit.getDeletedAt() != null) {
            throw new AppException(ErrorCode.NOT_FOUND, "计租单元不存在: " + unitId);
        }
        return unit;
    }
}
