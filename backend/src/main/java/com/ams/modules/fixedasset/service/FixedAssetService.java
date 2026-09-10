package com.ams.modules.fixedasset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.fixedasset.entity.FaInventoryItem;
import com.ams.modules.fixedasset.entity.FaInventoryPlan;
import com.ams.modules.fixedasset.entity.FixedAsset;
import com.ams.modules.fixedasset.mapper.FaInventoryItemMapper;
import com.ams.modules.fixedasset.mapper.FaInventoryPlanMapper;
import com.ams.modules.fixedasset.mapper.FixedAssetMapper;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 固定资产（FR-FA-*）：台账状态流转 + 盘点计划 MVP。
 */
@Service
public class FixedAssetService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final FixedAssetMapper assetMapper;
    private final FaInventoryPlanMapper planMapper;
    private final FaInventoryItemMapper itemMapper;
    private final RbacService rbacService;

    public FixedAssetService(
            FixedAssetMapper assetMapper,
            FaInventoryPlanMapper planMapper,
            FaInventoryItemMapper itemMapper,
            RbacService rbacService) {
        this.assetMapper = assetMapper;
        this.planMapper = planMapper;
        this.itemMapper = itemMapper;
        this.rbacService = rbacService;
    }

    public PageResult<FixedAsset> page(long page, long pageSize, String keyword, String status, Long companyId) {
        LambdaQueryWrapper<FixedAsset> wrapper = new LambdaQueryWrapper<FixedAsset>()
                .eq(status != null, FixedAsset::getStatus, status)
                .eq(companyId != null, FixedAsset::getCompanyId, companyId)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(FixedAsset::getName, keyword)
                                .or().like(FixedAsset::getAssetNo, keyword))
                .orderByDesc(FixedAsset::getId);
        // 全局公司切换：固定资产按所属公司收敛
        rbacService.applyCompanyScope(wrapper, SecurityUtils.current(), FixedAsset::getCompanyId);
        Page<FixedAsset> result = assetMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public FixedAsset get(Long id) {
        FixedAsset a = assetMapper.selectById(id);
        if (a == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return a;
    }

    public FixedAsset create(FixedAsset asset) {
        if (asset.getStatus() == null) {
            asset.setStatus("in_stock");
        }
        if (asset.getCreatedAt() == null) {
            asset.setCreatedAt(LocalDateTime.now());
        }
        assetMapper.insert(asset);
        return asset;
    }

    public FixedAsset update(Long id, FixedAsset asset) {
        asset.setId(id);
        assetMapper.updateById(asset);
        return get(id);
    }

    public void delete(Long id) {
        assetMapper.deleteById(id);
    }

    /** 状态流转：in_stock → in_use/loaned/disposed；in_use/loaned → in_stock/disposed。 */
    @Transactional
    public FixedAsset transition(Long id, String toStatus, String userName) {
        FixedAsset asset = get(id);
        String from = asset.getStatus() == null ? "in_stock" : asset.getStatus();
        if (!canTransition(from, toStatus)) {
            throw new AppException(ErrorCode.CONFLICT, "固资状态不可从 " + from + " 到 " + toStatus);
        }
        asset.setStatus(toStatus);
        if (userName != null) {
            asset.setUserName(userName);
        }
        if ("in_stock".equals(toStatus)) {
            asset.setUserName(null);
        }
        assetMapper.updateById(asset);
        return asset;
    }

    public Map<String, Object> summary(Long companyId) {
        List<FixedAsset> list = assetMapper.selectList(
                new LambdaQueryWrapper<FixedAsset>()
                        .eq(companyId != null, FixedAsset::getCompanyId, companyId));
        BigDecimal original = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        Map<String, Long> byStatus = new HashMap<>();
        for (FixedAsset a : list) {
            original = original.add(a.getOriginalValue() == null ? BigDecimal.ZERO : a.getOriginalValue());
            net = net.add(a.getNetValue() == null ? BigDecimal.ZERO : a.getNetValue());
            String s = a.getStatus() == null ? "unknown" : a.getStatus();
            byStatus.merge(s, 1L, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", list.size());
        result.put("originalValue", original);
        result.put("netValue", net);
        result.put("byStatus", byStatus);
        return result;
    }

    @Transactional
    public FaInventoryPlan createPlan(Long companyId, String title, LocalDate plannedDate) {
        FaInventoryPlan plan = new FaInventoryPlan();
        plan.setPlanNo("PD" + LocalDate.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        plan.setCompanyId(companyId);
        plan.setTitle(title == null ? "固资盘点" : title);
        plan.setStatus("draft");
        plan.setPlannedDate(plannedDate == null ? LocalDate.now() : plannedDate);
        plan.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        plan.setCreatedAt(LocalDateTime.now());
        planMapper.insert(plan);

        List<FixedAsset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<FixedAsset>()
                        .eq(companyId != null, FixedAsset::getCompanyId, companyId)
                        .ne(FixedAsset::getStatus, "disposed"));
        for (FixedAsset a : assets) {
            FaInventoryItem item = new FaInventoryItem();
            item.setPlanId(plan.getId());
            item.setFixedAssetId(a.getId());
            item.setBookStatus(a.getStatus());
            item.setActualStatus("pending");
            item.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(item);
        }
        return plan;
    }

    @Transactional
    public FaInventoryPlan startCounting(Long planId) {
        FaInventoryPlan plan = requirePlan(planId);
        plan.setStatus("counting");
        planMapper.updateById(plan);
        return plan;
    }

    @Transactional
    public FaInventoryItem scan(Long planId, Long fixedAssetId, String actualStatus, String remark) {
        FaInventoryPlan plan = requirePlan(planId);
        if (!"counting".equals(plan.getStatus()) && !"draft".equals(plan.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "盘点单不可扫码");
        }
        if ("draft".equals(plan.getStatus())) {
            plan.setStatus("counting");
            planMapper.updateById(plan);
        }
        FaInventoryItem item = itemMapper.selectOne(
                new LambdaQueryWrapper<FaInventoryItem>()
                        .eq(FaInventoryItem::getPlanId, planId)
                        .eq(FaInventoryItem::getFixedAssetId, fixedAssetId)
                        .last("LIMIT 1"));
        if (item == null) {
            // 盘盈
            item = new FaInventoryItem();
            item.setPlanId(planId);
            item.setFixedAssetId(fixedAssetId);
            item.setBookStatus("none");
            item.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(item);
            actualStatus = actualStatus == null ? "surplus" : actualStatus;
        }
        String result = actualStatus == null ? "matched" : actualStatus;
        item.setActualStatus(result);
        item.setRemark(remark);
        item.setScannedAt(LocalDateTime.now());
        itemMapper.updateById(item);
        return item;
    }

    @Transactional
    public FaInventoryPlan close(Long planId) {
        FaInventoryPlan plan = requirePlan(planId);
        List<FaInventoryItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<FaInventoryItem>().eq(FaInventoryItem::getPlanId, planId));
        for (FaInventoryItem item : items) {
            if ("pending".equals(item.getActualStatus()) || item.getActualStatus() == null) {
                item.setActualStatus("missing");
                itemMapper.updateById(item);
            }
            if ("missing".equals(item.getActualStatus()) || "deficit".equals(item.getActualStatus())) {
                FixedAsset fa = assetMapper.selectById(item.getFixedAssetId());
                if (fa != null && !"disposed".equals(fa.getStatus())) {
                    // 盘亏仅标记备注，不自动处置
                    fa.setLocation((fa.getLocation() == null ? "" : fa.getLocation() + ";")
                            + "inventory_diff:" + plan.getPlanNo());
                    assetMapper.updateById(fa);
                }
            }
        }
        plan.setStatus("closed");
        plan.setClosedAt(LocalDateTime.now());
        planMapper.updateById(plan);
        return plan;
    }

    public List<FaInventoryPlan> listPlans(Long companyId) {
        LambdaQueryWrapper<FaInventoryPlan> wrapper = new LambdaQueryWrapper<FaInventoryPlan>()
                .eq(companyId != null, FaInventoryPlan::getCompanyId, companyId)
                .orderByDesc(FaInventoryPlan::getId);
        // 全局公司切换：盘点计划按所属公司收敛
        rbacService.applyCompanyScope(wrapper, SecurityUtils.current(), FaInventoryPlan::getCompanyId);
        return planMapper.selectList(wrapper);
    }

    public List<FaInventoryItem> listItems(Long planId) {
        return itemMapper.selectList(
                new LambdaQueryWrapper<FaInventoryItem>()
                        .eq(FaInventoryItem::getPlanId, planId)
                        .orderByAsc(FaInventoryItem::getId));
    }

    private FaInventoryPlan requirePlan(Long id) {
        FaInventoryPlan plan = planMapper.selectById(id);
        if (plan == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "盘点计划不存在");
        }
        return plan;
    }

    private static boolean canTransition(String from, String to) {
        return switch (from) {
            case "in_stock" -> "in_use".equals(to) || "loaned".equals(to) || "disposed".equals(to);
            case "in_use", "loaned" -> "in_stock".equals(to) || "disposed".equals(to) || "in_use".equals(to);
            default -> false;
        };
    }
}
