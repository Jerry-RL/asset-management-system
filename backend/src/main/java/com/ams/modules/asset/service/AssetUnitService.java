package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.AssetLeaseGroups;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.UnitNoGenerator;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetStructureLog;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.AssetStructureLogMapper;
import com.ams.modules.asset.mapper.AssetUnitMapper;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计租单元服务：维护「资产 → 单元」这一层的面积预算、拆分与合并（ADR-0019 决策 A1）。
 *
 * <p>不变量（本类是唯一维护入口）：
 * <ul>
 *   <li><b>INV-1</b>：Σ 有效单元面积 ≤ 资产面积（{@code asset.lease_area} 优先，回退 {@code asset.area}）；</li>
 *   <li><b>INV-2</b>：每个资产至少一个有效单元（保证占用表的 {@code asset_unit_id} 可 NOT NULL）；</li>
 *   <li><b>INV-3</b>：存在生效占用的单元不可拆分/合并（否则占用会失去标的）。</li>
 * </ul>
 *
 * <p><b>拆分与合并必须成对存在</b>（ADR-0021 规则 S12）：{@link #merge} 是 {@link #split}
 * 的逆操作，拆错了要靠它回滚。只提供拆分而不提供合并，等于给出一个单向、不可撤销的高危操作。
 *
 * <p><b>前置判定一律以占用表为准</b>，不读 {@code asset.lease_control_status} / {@code unit_status}
 * 这两个物化派生列 —— 派生列存在短暂不一致窗口，拿它做门禁会在"状态尚未刷新"时放行
 * （ADR-0021 缺陷 D-04）。
 */
@Service
public class AssetUnitService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");

    private final AssetUnitMapper unitMapper;
    private final AssetMapper assetMapper;
    private final AssetOccupancyService occupancyService;
    private final LeaseListingMapper listingMapper;
    private final LeaseStatusDeriver deriver;
    private final CertificateService certificateService;
    private final AssetStructureLogMapper structureLogMapper;
    private final ObjectMapper objectMapper;

    public AssetUnitService(
            AssetUnitMapper unitMapper,
            AssetMapper assetMapper,
            AssetOccupancyService occupancyService,
            LeaseListingMapper listingMapper,
            LeaseStatusDeriver deriver,
            CertificateService certificateService,
            AssetStructureLogMapper structureLogMapper,
            ObjectMapper objectMapper) {
        this.unitMapper = unitMapper;
        this.assetMapper = assetMapper;
        this.occupancyService = occupancyService;
        this.listingMapper = listingMapper;
        this.deriver = deriver;
        this.certificateService = certificateService;
        this.structureLogMapper = structureLogMapper;
        this.objectMapper = objectMapper;
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
     * <p>前置条件（全部以<b>占用表</b>判定）：无未收口占用（含 {@code reserving} 预留）、
     * 无生效招租发布、资产未在押。原单元软删除以保留历史身份。
     *
     * <p>面积与底价<b>按比例分摊</b>到子单元，不做整份复制 —— 否则拆分后底价总额会放大 N 倍
     * （ADR-0021 缺陷 D-06），资产价值口径同样如此处理。
     *
     * @param areas 子单元面积（至少 2 个，合计须等于原单元面积，容差 {@value #TOLERANCE}）
     */
    @Transactional
    public List<AssetUnit> split(Long unitId, List<BigDecimal> areas, String remark) {
        AssetUnit src = require(unitId);
        if (areas == null || areas.size() < 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "至少拆分为 2 个单元");
        }
        // 权属负担与拆分互斥（ADR-0021 规则 S5 / ADR-0019「关键区分③」）
        certificateService.assertNotMortgaged(src.getAssetId());
        if (occupancyService.hasOpenOccupancy(unitId)) {
            throw new AppException(ErrorCode.CONFLICT, "单元存在生效占用，须先退租或解除占用后再拆分");
        }
        long activeListings = listingMapper.selectCount(new LambdaQueryWrapper<LeaseListing>()
                .eq(LeaseListing::getAssetUnitId, unitId)
                .eq(LeaseListing::getStatus, "active"));
        if (activeListings > 0) {
            throw new AppException(ErrorCode.CONFLICT, "单元存在生效招租发布，须先关闭后再拆分");
        }

        BigDecimal srcArea = src.getArea() == null ? BigDecimal.ZERO : src.getArea();
        /*
         * 占位单元（area = 0）一律拒绝拆分。
         *
         * 旧实现是「srcArea > 0 才校验守恒」，等价于对 0 面积单元**跳过**校验：拆它既不校验
         * 面积、又会生成一批 0 面积子单元，而 INV-1（Σ 单元面积 ≤ 资产面积）也就失去了意义
         * （ADR-0021 缺陷 D-15）。这里改为显式拒绝，提示先补面积。
         */
        if (srcArea.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "单元面积为 0（面积待补的占位单元），请先补齐面积后再拆分");
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal a : areas) {
            if (a == null || a.compareTo(BigDecimal.ZERO) <= 0) {
                throw new AppException(ErrorCode.BAD_REQUEST, "子单元面积必须大于 0");
            }
            sum = sum.add(a);
        }
        if (sum.subtract(srcArea).abs().compareTo(TOLERANCE) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "子单元面积之和须等于原单元面积（原 " + srcArea.toPlainString()
                            + "，合计 " + sum.toPlainString() + "）");
        }

        List<AssetUnit> children = new ArrayList<>();
        List<Map<String, Object>> mapping = new ArrayList<>();
        int idx = 1;
        for (BigDecimal a : areas) {
            AssetUnit child = new AssetUnit();
            child.setAssetId(src.getAssetId());
            child.setUnitNo(UnitNoGenerator.childOf(src.getUnitNo(), idx));
            child.setUnitName((src.getUnitName() == null ? "单元" : src.getUnitName()) + "-" + idx);
            child.setArea(a.setScale(2, RoundingMode.HALF_UP));
            // 可租面积按同一比例拆分（保留原单元「可租 < 面积」的差异），而非无条件等于 area
            child.setRentableArea(prorate(src.getRentableArea(), srcArea, child.getArea()));
            // 底价按面积分摊，不整份复制（D-06）
            child.setBaseRent(prorate(src.getBaseRent(), srcArea, child.getArea()));
            child.setUnitStatus(LeaseControlStatus.VACANT);
            child.setFloorNo(src.getFloorNo());
            child.setSort(src.getSort() == null ? idx : src.getSort() * 100 + idx);
            child.setVersion(0);
            child.setRemark(remark);
            unitMapper.insert(child);
            children.add(child);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("childUnitId", child.getId());
            row.put("childUnitNo", child.getUnitNo());
            row.put("area", child.getArea());
            mapping.add(row);
            idx++;
        }

        src.setDeletedAt(LocalDateTime.now());
        src.setRemark(append(src.getRemark(), "已拆分为 " + areas.size() + " 个子单元"));
        unitMapper.updateById(src);

        writeStructureLog("unit_split", src.getAssetId(), List.of(unitId),
                children.stream().map(AssetUnit::getId).toList(), mapping, remark);

        deriver.refresh(src.getAssetId());
        return children;
    }

    /**
     * 合并单元 —— 把同一资产下多个「全部空置」的单元合并为一个（ADR-0021 决策 C）。
     *
     * <p>它是 {@link #split} 的<b>逆操作</b>，用于撤销误拆（规则 S12）。不做"结果单元另建、
     * 源单元全删"：改为<b>保留排序最靠前的一个单元</b>作为结果，其余软删 —— 这样结果单元
     * 的编码与身份已经存在，下游引用最少，也避免每次合并都产生一个新编码。
     *
     * <p>前置：同资产、每个单元都无未收口占用（含 {@code reserving}）、无生效招租发布、
     * 资产未在押。面积与底价<b>相加</b>（不做平均），可租面积同为相加。
     *
     * @param unitIds 待合并的单元 ID（至少 2 个，必须属于同一资产）
     * @return 合并后的结果单元
     */
    @Transactional
    public AssetUnit merge(List<Long> unitIds, String remark) {
        if (unitIds == null || unitIds.size() < 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "至少合并 2 个单元");
        }
        // 去重：同一单元传两次会让下面的"面积相加"把它重复计入
        List<Long> ids = unitIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() < 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "至少合并 2 个不同的单元");
        }

        List<AssetUnit> units = new ArrayList<>();
        for (Long id : ids) {
            units.add(require(id));
        }
        Long assetId = units.get(0).getAssetId();
        for (AssetUnit u : units) {
            if (!assetId.equals(u.getAssetId())) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "仅支持同一资产内的单元合并：" + u.getUnitNo() + " 不属于该资产");
            }
        }
        certificateService.assertNotMortgaged(assetId);

        BigDecimal totalArea = BigDecimal.ZERO;
        BigDecimal totalRentable = BigDecimal.ZERO;
        boolean hasRentable = false;
        BigDecimal totalBaseRent = BigDecimal.ZERO;
        boolean hasBaseRent = false;
        for (AssetUnit u : units) {
            if (occupancyService.hasOpenOccupancy(u.getId())) {
                throw new AppException(ErrorCode.CONFLICT,
                        "单元存在生效占用，须先退租或解除占用后再合并：" + u.getUnitNo());
            }
            long activeListings = listingMapper.selectCount(new LambdaQueryWrapper<LeaseListing>()
                    .eq(LeaseListing::getAssetUnitId, u.getId())
                    .eq(LeaseListing::getStatus, "active"));
            if (activeListings > 0) {
                throw new AppException(ErrorCode.CONFLICT,
                        "单元存在生效招租发布，须先关闭后再合并：" + u.getUnitNo());
            }
            totalArea = totalArea.add(nvl(u.getArea()));
            if (u.getRentableArea() != null) {
                hasRentable = true;
                totalRentable = totalRentable.add(u.getRentableArea());
            }
            if (u.getBaseRent() != null) {
                hasBaseRent = true;
                totalBaseRent = totalBaseRent.add(u.getBaseRent());
            }
        }

        // 结果单元 = 排序最靠前者：编码/身份已存在，下游引用最少
        units.sort(Comparator
                .comparingInt((AssetUnit u) -> u.getSort() == null ? 0 : u.getSort())
                .thenComparing(AssetUnit::getId));
        AssetUnit target = units.get(0);
        target.setArea(totalArea.setScale(2, RoundingMode.HALF_UP));
        target.setRentableArea(hasRentable ? totalRentable.setScale(2, RoundingMode.HALF_UP) : null);
        target.setBaseRent(hasBaseRent ? totalBaseRent.setScale(2, RoundingMode.HALF_UP) : null);
        target.setRemark(append(target.getRemark(), remark));

        List<Long> sourceIds = new ArrayList<>();
        List<Map<String, Object>> mapping = new ArrayList<>();
        for (int i = 1; i < units.size(); i++) {
            AssetUnit src = units.get(i);
            src.setDeletedAt(LocalDateTime.now());
            src.setRemark(append(src.getRemark(), "已合并入单元 " + target.getUnitNo()));
            unitMapper.updateById(src);
            sourceIds.add(src.getId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceUnitId", src.getId());
            row.put("sourceUnitNo", src.getUnitNo());
            row.put("targetUnitId", target.getId());
            row.put("targetUnitNo", target.getUnitNo());
            mapping.add(row);
        }
        unitMapper.updateById(target);

        writeStructureLog("unit_merge", assetId, sourceIds, List.of(target.getId()), mapping, remark);

        deriver.refresh(assetId);
        return target;
    }

    /**
     * 按面积比例分摊面积/金额类字段（ADR-0021 缺陷 D-06）。
     *
     * <p>原值为 {@code null} 或原面积非正时返回 {@code null} —— 保持「未维护」语义，
     * 不用 0 冒充（0 与 null 在下游的口径不同）。
     */
    private BigDecimal prorate(BigDecimal source, BigDecimal sourceArea, BigDecimal targetArea) {
        if (source == null || sourceArea == null || sourceArea.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return source.multiply(targetArea).divide(sourceArea, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 追加备注：已有备注与新增说明之间用「；」分隔，避免两次操作的说明黏成一句。 */
    private String append(String existing, String addition) {
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        return (existing == null || existing.isBlank() ? "" : existing + "；") + addition;
    }

    /**
     * 结构变更留痕（FR-MDM-003「任意时点可查资产结构树」）。
     *
     * <p>{@code source/result} 两列存的仍是<b>资产 ID</b>（列名与既有 split/merge 的口径一致），
     * 单元粒度信息放在 {@code mapping_json} 里 —— 单元拆分/合并属于同一资产，写两遍资产 ID
     * 不会丢信息，反而让「按资产查结构变更历史」这一查询对两种操作口径统一。
     */
    private void writeStructureLog(String opType, Long assetId, List<Long> sources,
            List<Long> results, List<Map<String, Object>> mapping, String remark) {
        AssetStructureLog log = new AssetStructureLog();
        log.setOpType(opType);
        log.setSourceAssetIds(String.valueOf(List.of(assetId)));
        log.setResultAssetIds(String.valueOf(List.of(assetId)));
        try {
            log.setMappingJson(objectMapper.writeValueAsString(Map.of(
                    "sourceUnitIds", sources,
                    "resultUnitIds", results,
                    "units", mapping)));
        } catch (Exception e) {
            log.setMappingJson(String.valueOf(mapping));
        }
        log.setRemark(remark);
        log.setOperatorId(SecurityUtils.currentUserIdOrNull());
        log.setCreatedAt(LocalDateTime.now());
        structureLogMapper.insert(log);
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

    /**
     * 按 ID 取有效单元，供接口层解析归属后做数据权限校验。
     *
     * <p>单元自己不承载公司列，归属只能经 {@code asset_id} 推出（与
     * {@code OwnershipResolver.ofAsset} 同一口径）。接口层必须先拿到 {@code assetId}
     * 才能断言「这个单元在当前账号可见范围内」，否则持有任一 unitId 就能操作范围外的资产。
     */
    public AssetUnit getUnit(Long unitId) {
        return require(unitId);
    }
}
