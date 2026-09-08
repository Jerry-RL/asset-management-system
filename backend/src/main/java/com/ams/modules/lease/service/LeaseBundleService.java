package com.ams.modules.lease.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.lease.entity.LeaseBundle;
import com.ams.modules.lease.entity.LeaseBundleItem;
import com.ams.modules.lease.mapper.LeaseBundleItemMapper;
import com.ams.modules.lease.mapper.LeaseBundleMapper;
import com.ams.modules.pricing.PlanGenerator;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组合/拆分租赁计费（FR-OPS-006）。
 */
@Service
public class LeaseBundleService {

    private final LeaseBundleMapper bundleMapper;
    private final LeaseBundleItemMapper itemMapper;
    private final AssetMapper assetMapper;
    private final ContractMapper contractMapper;
    private final LeaseControlService leaseControlService;
    private final TenantService tenantService;
    private final PlanGenerator planGenerator;

    public LeaseBundleService(
            LeaseBundleMapper bundleMapper,
            LeaseBundleItemMapper itemMapper,
            AssetMapper assetMapper,
            ContractMapper contractMapper,
            LeaseControlService leaseControlService,
            TenantService tenantService,
            PlanGenerator planGenerator) {
        this.bundleMapper = bundleMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.contractMapper = contractMapper;
        this.leaseControlService = leaseControlService;
        this.tenantService = tenantService;
        this.planGenerator = planGenerator;
    }

    public List<LeaseBundle> list(String bundleType) {
        return bundleMapper.selectList(
                new LambdaQueryWrapper<LeaseBundle>()
                        .eq(bundleType != null, LeaseBundle::getBundleType, bundleType)
                        .orderByDesc(LeaseBundle::getId));
    }

    public Map<String, Object> detail(Long bundleId) {
        LeaseBundle bundle = require(bundleId);
        List<LeaseBundleItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<LeaseBundleItem>().eq(LeaseBundleItem::getBundleId, bundleId));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bundle", bundle);
        map.put("items", items);
        return map;
    }

    /**
     * 组合租赁：多资产打包。
     * rentMode=package：整包租金挂主合同，子合同按面积分摊可追溯；
     * rentMode=apportion：按面积占比分摊 totalRent 到各资产合同。
     */
    @Transactional
    public Map<String, Object> createCombo(
            Long tenantId,
            List<Map<String, Object>> assets,
            BigDecimal totalRent,
            String rentMode,
            LocalDate startDate,
            LocalDate endDate,
            String remark) {
        if (tenantId == null || assets == null || assets.size() < 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "组合租赁至少 2 个资产且须指定租户");
        }
        tenantService.assertNotBlacklisted(tenantId);
        String mode = rentMode == null || rentMode.isBlank() ? "apportion" : rentMode;
        BigDecimal sumArea = BigDecimal.ZERO;
        List<Asset> resolved = new ArrayList<>();
        for (Map<String, Object> row : assets) {
            Long assetId = Long.valueOf(row.get("assetId").toString());
            Asset asset = assetMapper.selectById(assetId);
            if (asset == null) {
                throw new AppException(ErrorCode.NOT_FOUND, "资产不存在: " + assetId);
            }
            if (!LeaseControlStatus.VACANT.equals(asset.getLeaseControlStatus())
                    && !LeaseControlStatus.LEASING.equals(asset.getLeaseControlStatus())) {
                throw new AppException(ErrorCode.CONFLICT, "仅空置/招租中资产可组合租赁: " + asset.getAssetNo());
            }
            BigDecimal leaseArea = row.get("leaseArea") == null
                    ? asset.getArea()
                    : new BigDecimal(row.get("leaseArea").toString());
            if (leaseArea == null || leaseArea.compareTo(BigDecimal.ZERO) <= 0) {
                throw new AppException(ErrorCode.BAD_REQUEST, "租赁面积无效");
            }
            if (asset.getArea() != null && leaseArea.compareTo(asset.getArea()) > 0) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "租赁面积超出资产面积: " + asset.getAssetNo());
            }
            sumArea = sumArea.add(leaseArea);
            resolved.add(asset);
            row.put("_leaseArea", leaseArea);
        }

        LeaseBundle bundle = newBundle("combo", mode, totalRent, remark);
        bundleMapper.insert(bundle);

        Contract master = null;
        List<Contract> children = new ArrayList<>();
        for (int idx = 0; idx < assets.size(); idx++) {
            Map<String, Object> row = assets.get(idx);
            Asset asset = resolved.get(idx);
            BigDecimal leaseArea = (BigDecimal) row.get("_leaseArea");
            BigDecimal ratio = sumArea.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : leaseArea.divide(sumArea, 6, RoundingMode.HALF_UP);
            BigDecimal rentShare;
            if ("package".equals(mode)) {
                rentShare = idx == 0 && totalRent != null ? totalRent : BigDecimal.ZERO;
            } else {
                rentShare = (totalRent == null ? BigDecimal.ZERO : totalRent)
                        .multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            }

            Contract c = newDraft(asset.getId(), tenantId, leaseArea, rentShare, startDate, endDate);
            c.setBundleId(bundle.getId());
            c.setLeaseMode("combo");
            c.setStatus(ContractStatus.ACTIVE);
            c.setContractType("combo");
            contractMapper.insert(c);
            planGenerator.generate(c.getId());
            leaseControlService.transition(asset.getId(), LeaseControlStatus.LEASED,
                    "lease_bundle", bundle.getId(), "组合租赁生效");

            LeaseBundleItem item = new LeaseBundleItem();
            item.setBundleId(bundle.getId());
            item.setAssetId(asset.getId());
            item.setTenantId(tenantId);
            item.setContractId(c.getId());
            item.setLeaseArea(leaseArea);
            item.setRentAmount(rentShare);
            item.setAreaRatio(ratio);
            item.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(item);

            if (master == null) {
                master = c;
            } else {
                c.setParentContractId(master.getId());
                contractMapper.updateById(c);
                children.add(c);
            }
        }

        bundle.setMasterContractId(master.getId());
        bundle.setStatus("active");
        bundle.setUpdatedAt(LocalDateTime.now());
        bundleMapper.updateById(bundle);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bundle", bundle);
        result.put("masterContract", master);
        result.put("childContracts", children);
        result.put("items", itemMapper.selectList(
                new LambdaQueryWrapper<LeaseBundleItem>().eq(LeaseBundleItem::getBundleId, bundle.getId())));
        return result;
    }

    /**
     * 拆分租赁：一资产多租户，面积占比校验，账单按租户独立合同生成。
     */
    @Transactional
    public Map<String, Object> createSplit(
            Long assetId,
            List<Map<String, Object>> tenants,
            String rentMode,
            LocalDate startDate,
            LocalDate endDate,
            String remark) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        if (!LeaseControlStatus.VACANT.equals(asset.getLeaseControlStatus())
                && !LeaseControlStatus.LEASING.equals(asset.getLeaseControlStatus())
                && !LeaseControlStatus.PARTIAL_LEASED.equals(asset.getLeaseControlStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前租控状态不可拆分租赁");
        }
        if (tenants == null || tenants.size() < 2) {
            throw new AppException(ErrorCode.BAD_REQUEST, "拆分租赁至少 2 个租户");
        }
        String mode = rentMode == null || rentMode.isBlank() ? "area_ratio" : rentMode;
        BigDecimal sumArea = BigDecimal.ZERO;
        for (Map<String, Object> row : tenants) {
            BigDecimal area = new BigDecimal(row.get("leaseArea").toString());
            if (area.compareTo(BigDecimal.ZERO) <= 0) {
                throw new AppException(ErrorCode.BAD_REQUEST, "租赁面积须大于0");
            }
            sumArea = sumArea.add(area);
        }
        if (asset.getArea() != null && sumArea.compareTo(asset.getArea()) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "拆分租赁面积之和不可超过资产总面积");
        }

        LeaseBundle bundle = newBundle("split", mode, null, remark);
        bundleMapper.insert(bundle);

        List<Contract> contracts = new ArrayList<>();
        for (Map<String, Object> row : tenants) {
            Long tenantId = Long.valueOf(row.get("tenantId").toString());
            tenantService.assertNotBlacklisted(tenantId);
            BigDecimal area = new BigDecimal(row.get("leaseArea").toString());
            BigDecimal rent = row.get("rentAmount") == null
                    ? BigDecimal.ZERO
                    : new BigDecimal(row.get("rentAmount").toString());
            BigDecimal ratio = asset.getArea() == null || asset.getArea().compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : area.divide(asset.getArea(), 6, RoundingMode.HALF_UP);

            Contract c = newDraft(assetId, tenantId, area, rent, startDate, endDate);
            c.setBundleId(bundle.getId());
            c.setLeaseMode("split");
            c.setStatus(ContractStatus.ACTIVE);
            c.setContractType("split");
            contractMapper.insert(c);
            planGenerator.generate(c.getId());
            contracts.add(c);

            LeaseBundleItem item = new LeaseBundleItem();
            item.setBundleId(bundle.getId());
            item.setAssetId(assetId);
            item.setTenantId(tenantId);
            item.setContractId(c.getId());
            item.setLeaseArea(area);
            item.setRentAmount(rent);
            item.setAreaRatio(ratio);
            item.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(item);
        }

        String target = sumArea.compareTo(asset.getArea() == null ? sumArea : asset.getArea()) < 0
                ? LeaseControlStatus.PARTIAL_LEASED
                : LeaseControlStatus.LEASED;
        if (!target.equals(asset.getLeaseControlStatus())) {
            leaseControlService.transition(assetId, target, "lease_bundle", bundle.getId(), "拆分租赁生效");
        }

        bundle.setStatus("active");
        bundle.setMasterContractId(contracts.get(0).getId());
        bundle.setUpdatedAt(LocalDateTime.now());
        bundleMapper.updateById(bundle);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bundle", bundle);
        result.put("contracts", contracts);
        result.put("items", itemMapper.selectList(
                new LambdaQueryWrapper<LeaseBundleItem>().eq(LeaseBundleItem::getBundleId, bundle.getId())));
        return result;
    }

    private LeaseBundle newBundle(String type, String mode, BigDecimal totalRent, String remark) {
        LeaseBundle bundle = new LeaseBundle();
        bundle.setBundleNo("LB" + System.currentTimeMillis());
        bundle.setBundleType(type);
        bundle.setRentMode(mode);
        bundle.setTotalRent(totalRent);
        bundle.setStatus("draft");
        bundle.setRemark(remark);
        bundle.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        bundle.setCreatedAt(LocalDateTime.now());
        return bundle;
    }

    private Contract newDraft(
            Long assetId, Long tenantId, BigDecimal area, BigDecimal rent,
            LocalDate start, LocalDate end) {
        Contract c = new Contract();
        c.setAssetId(assetId);
        c.setTenantId(tenantId);
        c.setLeaseArea(area);
        c.setRentAmount(rent);
        c.setStartDate(start == null ? LocalDate.now() : start);
        c.setEndDate(end == null ? LocalDate.now().plusYears(1) : end);
        c.setRentType("fixed_monthly");
        c.setPaymentCycle("monthly");
        c.setContractNo("CT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        c.setVersion(1);
        c.setPaymentStatus("unpaid");
        return c;
    }

    private LeaseBundle require(Long id) {
        LeaseBundle b = bundleMapper.selectById(id);
        if (b == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "租赁组合不存在");
        }
        return b;
    }
}
