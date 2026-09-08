package com.ams.modules.audit.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.AssetQrService;
import com.ams.modules.audit.entity.AssetAuditItem;
import com.ams.modules.audit.entity.AssetAuditPlan;
import com.ams.modules.audit.mapper.AssetAuditItemMapper;
import com.ams.modules.audit.mapper.AssetAuditPlanMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 经营性资产盘点（FR-AST-AUDIT-*）：计划、明细、扫码、差异审批、账实报告。
 */
@Service
public class AssetAuditService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AssetAuditPlanMapper planMapper;
    private final AssetAuditItemMapper itemMapper;
    private final AssetMapper assetMapper;
    private final ApprovalEngine approvalEngine;
    private final AssetQrService assetQrService;

    public AssetAuditService(
            AssetAuditPlanMapper planMapper,
            AssetAuditItemMapper itemMapper,
            AssetMapper assetMapper,
            ApprovalEngine approvalEngine,
            AssetQrService assetQrService) {
        this.planMapper = planMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.approvalEngine = approvalEngine;
        this.assetQrService = assetQrService;
    }

    public PageResult<AssetAuditPlan> pagePlans(long page, long pageSize, String status) {
        Page<AssetAuditPlan> result = planMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<AssetAuditPlan>()
                        .eq(status != null, AssetAuditPlan::getStatus, status)
                        .orderByDesc(AssetAuditPlan::getId));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public AssetAuditPlan getPlan(Long id) {
        AssetAuditPlan plan = planMapper.selectById(id);
        if (plan == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "盘点计划不存在");
        }
        return plan;
    }

    @Transactional
    public AssetAuditPlan createPlan(AssetAuditPlan plan) {
        plan.setId(null);
        plan.setPlanNo("PD" + LocalDate.now().format(NO_FMT)
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        if (plan.getScopeType() == null) {
            plan.setScopeType("full");
        }
        plan.setStatus("draft");
        planMapper.insert(plan);
        return plan;
    }

    /** 启动盘点：按项目拉取资产生成明细。 */
    @Transactional
    public AssetAuditPlan startPlan(Long planId) {
        AssetAuditPlan plan = getPlan(planId);
        if (!"draft".equals(plan.getStatus()) && !"cancelled".equals(plan.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅草稿可启动");
        }
        LambdaQueryWrapper<Asset> qw = new LambdaQueryWrapper<Asset>()
                .eq(plan.getProjectId() != null, Asset::getProjectId, plan.getProjectId())
                .orderByAsc(Asset::getId);
        if ("sample".equals(plan.getScopeType())) {
            qw.last("LIMIT 50");
        }
        List<Asset> assets = assetMapper.selectList(qw);
        if (assets.isEmpty()) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "范围内无资产可盘点");
        }
        for (Asset asset : assets) {
            AssetAuditItem item = new AssetAuditItem();
            item.setPlanId(planId);
            item.setAssetId(asset.getId());
            item.setExpectedStatus(asset.getLeaseControlStatus());
            item.setVarianceType("none");
            item.setStatus("pending");
            itemMapper.insert(item);
        }
        plan.setStatus("in_progress");
        planMapper.updateById(plan);
        return plan;
    }

    public List<AssetAuditItem> listItems(Long planId, String status) {
        return itemMapper.selectList(
                new LambdaQueryWrapper<AssetAuditItem>()
                        .eq(AssetAuditItem::getPlanId, planId)
                        .eq(status != null, AssetAuditItem::getStatus, status)
                        .orderByAsc(AssetAuditItem::getId));
    }

    /** 工作端扫码盘点。 */
    @Transactional
    public AssetAuditItem scan(Long planId, String scanCode, String actualStatus, String remark) {
        AssetAuditPlan plan = getPlan(planId);
        if (!"in_progress".equals(plan.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "盘点未在进行中");
        }
        Asset asset = findAssetByScan(scanCode);
        AssetAuditItem item = itemMapper.selectOne(
                new LambdaQueryWrapper<AssetAuditItem>()
                        .eq(AssetAuditItem::getPlanId, planId)
                        .eq(AssetAuditItem::getAssetId, asset.getId())
                        .last("LIMIT 1"));
        if (item == null) {
            // 盘盈：范围内未列入的资产
            item = new AssetAuditItem();
            item.setPlanId(planId);
            item.setAssetId(asset.getId());
            item.setExpectedStatus(null);
            item.setActualStatus(actualStatus == null ? asset.getLeaseControlStatus() : actualStatus);
            item.setVarianceType("surplus");
            item.setStatus("variance");
            item.setScanCode(scanCode);
            item.setScannedAt(LocalDateTime.now());
            item.setScannedBy(SecurityUtils.currentUserIdOrNull());
            item.setRemark(remark);
            itemMapper.insert(item);
            approvalEngine.start("asset_audit_variance", item.getId());
            return item;
        }
        item.setActualStatus(actualStatus == null ? asset.getLeaseControlStatus() : actualStatus);
        item.setScanCode(scanCode);
        item.setScannedAt(LocalDateTime.now());
        item.setScannedBy(SecurityUtils.currentUserIdOrNull());
        item.setRemark(remark);
        boolean mismatch = item.getExpectedStatus() != null
                && !item.getExpectedStatus().equalsIgnoreCase(item.getActualStatus());
        if (mismatch) {
            item.setVarianceType("status_mismatch");
            item.setStatus("variance");
            itemMapper.updateById(item);
            approvalEngine.start("asset_audit_variance", item.getId());
        } else {
            item.setVarianceType("none");
            item.setStatus("scanned");
            itemMapper.updateById(item);
        }
        return item;
    }

    /** 标记未扫到的明细为盘亏。 */
    @Transactional
    public int markMissingAsDeficit(Long planId) {
        List<AssetAuditItem> pending = listItems(planId, "pending");
        for (AssetAuditItem item : pending) {
            item.setVarianceType("deficit");
            item.setStatus("variance");
            item.setRemark("盘点结束未扫到");
            itemMapper.updateById(item);
            approvalEngine.start("asset_audit_variance", item.getId());
        }
        return pending.size();
    }

    @Transactional
    public AssetAuditPlan completePlan(Long planId) {
        AssetAuditPlan plan = getPlan(planId);
        markMissingAsDeficit(planId);
        plan.setStatus("completed");
        planMapper.updateById(plan);
        return plan;
    }

    public Map<String, Object> report(Long planId) {
        AssetAuditPlan plan = getPlan(planId);
        List<AssetAuditItem> items = listItems(planId, null);
        long scanned = items.stream().filter(i -> "scanned".equals(i.getStatus())
                || "approved".equals(i.getStatus())).count();
        long variance = items.stream().filter(i -> "variance".equals(i.getStatus())
                || "surplus".equals(i.getVarianceType())
                || "deficit".equals(i.getVarianceType())
                || "status_mismatch".equals(i.getVarianceType())).count();
        Map<String, Object> report = new HashMap<>();
        report.put("plan", plan);
        report.put("total", items.size());
        report.put("scannedOk", scanned);
        report.put("varianceCount", variance);
        report.put("pending", items.stream().filter(i -> "pending".equals(i.getStatus())).count());
        report.put("items", items);
        return report;
    }

    @EventListener
    @Transactional
    public void onVarianceApproved(ApprovalCompletedEvent event) {
        if (!"asset_audit_variance".equals(event.getBizType())) {
            return;
        }
        AssetAuditItem item = itemMapper.selectById(event.getBizId());
        if (item == null) {
            return;
        }
        item.setStatus(event.isApproved() ? "approved" : "rejected");
        itemMapper.updateById(item);
    }

    private Asset findAssetByScan(String scanCode) {
        return assetQrService.resolveByScanContent(scanCode);
    }
}
