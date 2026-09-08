package com.ams.modules.lease.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.evaluation.service.EvaluationService;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.entity.TenderAnnouncement;
import com.ams.modules.lease.entity.TenderApplication;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.modules.lease.mapper.TenderAnnouncementMapper;
import com.ams.modules.lease.mapper.TenderApplicationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 招租管理（FR-LEASE-001/002）+ 公开招租、保证金退还与备案（FR-TENDER-*）。
 */
@Service
public class LeaseListingService {

    private final LeaseListingMapper listingMapper;
    private final TenderAnnouncementMapper announcementMapper;
    private final TenderApplicationMapper applicationMapper;
    private final AssetMapper assetMapper;
    private final LeaseControlService leaseControlService;
    private final TenantService tenantService;
    private final ObjectMapper objectMapper;

    public LeaseListingService(
            LeaseListingMapper listingMapper,
            TenderAnnouncementMapper announcementMapper,
            TenderApplicationMapper applicationMapper,
            AssetMapper assetMapper,
            LeaseControlService leaseControlService,
            TenantService tenantService,
            ObjectMapper objectMapper) {
        this.listingMapper = listingMapper;
        this.announcementMapper = announcementMapper;
        this.applicationMapper = applicationMapper;
        this.assetMapper = assetMapper;
        this.leaseControlService = leaseControlService;
        this.tenantService = tenantService;
        this.objectMapper = objectMapper;
    }

    public List<LeaseListing> listListings(String status) {
        return listingMapper.selectList(
                new LambdaQueryWrapper<LeaseListing>()
                        .eq(status != null, LeaseListing::getStatus, status)
                        .orderByDesc(LeaseListing::getId));
    }

    @Transactional
    public LeaseListing publish(LeaseListing listing) {
        Asset asset = assetMapper.selectById(listing.getAssetId());
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        leaseControlService.assertVacant(asset.getId());
        BigDecimal floor = EvaluationService.effectiveFloor(asset);
        if (listing.getRentAmount() == null && floor != null) {
            listing.setRentAmount(floor);
        }
        if (floor != null && listing.getRentAmount() != null
                && listing.getRentAmount().compareTo(floor) < 0
                && !Boolean.TRUE.equals(listing.getRentNegotiable())) {
            throw new AppException(ErrorCode.CONFLICT,
                    "挂牌租金低于评估/备案底价，请调高挂牌价或标记可议价后特批");
        }
        listing.setStatus("active");
        listing.setPublishedAt(LocalDateTime.now());
        listingMapper.insert(listing);
        leaseControlService.transition(asset.getId(), LeaseControlStatus.LEASING,
                "listing", listing.getId(), "发布招租");
        return listing;
    }

    public LeaseListing close(Long id) {
        LeaseListing listing = listingMapper.selectById(id);
        if (listing == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        listing.setStatus("closed");
        listing.setClosedAt(LocalDateTime.now());
        listingMapper.updateById(listing);
        return listing;
    }

    public TenderAnnouncement createAnnouncement(TenderAnnouncement announcement) {
        announcement.setStatus("open");
        announcementMapper.insert(announcement);
        return announcement;
    }

    public List<TenderAnnouncement> listAnnouncements(String status) {
        return announcementMapper.selectList(
                new LambdaQueryWrapper<TenderAnnouncement>()
                        .eq(status != null, TenderAnnouncement::getStatus, status)
                        .orderByDesc(TenderAnnouncement::getId));
    }

    public TenderApplication apply(Long announcementId, Long tenantId, Boolean depositPaid,
            BigDecimal depositAmount, Long materialsFileId) {
        tenantService.assertNotBlacklisted(tenantId);
        tenantService.assertCreditEligible(tenantId);
        TenderAnnouncement ann = announcementMapper.selectById(announcementId);
        if (ann == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "公告不存在");
        }
        if (!"open".equals(ann.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "公告已截止或关闭");
        }
        TenderApplication app = new TenderApplication();
        app.setAnnouncementId(announcementId);
        app.setTenantId(tenantId);
        app.setAuditStatus("pending");
        app.setDepositPaid(depositPaid != null && depositPaid);
        app.setDepositAmount(depositAmount);
        app.setDepositRefunded(false);
        app.setMaterialsFileId(materialsFileId);
        applicationMapper.insert(app);
        return app;
    }

    public List<TenderApplication> listApplications(Long announcementId) {
        return applicationMapper.selectList(
                new LambdaQueryWrapper<TenderApplication>()
                        .eq(TenderApplication::getAnnouncementId, announcementId)
                        .orderByAsc(TenderApplication::getId));
    }

    @Transactional
    public TenderApplication audit(Long applicationId, boolean approved, String comment, Integer rankNo) {
        TenderApplication app = requireApp(applicationId);
        app.setAuditStatus(approved ? "approved" : "rejected");
        app.setAuditComment(comment);
        app.setRankNo(rankNo);
        applicationMapper.updateById(app);
        if (!approved) {
            refundDeposit(app, "资格审查不符合，退还报名保证金");
        }
        return app;
    }

    @Transactional
    public TenderAnnouncement markFlowed(Long announcementId) {
        TenderAnnouncement ann = requireAnn(announcementId);
        ann.setStatus("flowed");
        ann.setResult("流标");
        announcementMapper.updateById(ann);
        refundNonWinners(announcementId, null, "流标退还报名保证金");
        buildFilingPackage(ann);
        return ann;
    }

    @Transactional
    public TenderAnnouncement finalizeResult(Long announcementId, Long winnerApplicationId) {
        TenderAnnouncement ann = requireAnn(announcementId);
        TenderApplication winner = requireApp(winnerApplicationId);
        if (!announcementId.equals(winner.getAnnouncementId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "中标报名不属于该公告");
        }
        winner.setResult("won");
        applicationMapper.updateById(winner);
        refundNonWinners(announcementId, winnerApplicationId, "未中标退还报名保证金");
        ann.setStatus("closed");
        ann.setResult("中标租户 " + winner.getTenantId());
        announcementMapper.updateById(ann);
        buildFilingPackage(ann);
        return ann;
    }

    /** 手动退还报名保证金（FR-TENDER-006）。 */
    @Transactional
    public TenderApplication refundDeposit(Long applicationId, String remark) {
        TenderApplication app = requireApp(applicationId);
        refundDeposit(app, remark == null ? "报名保证金退还" : remark);
        return app;
    }

    /** 备案材料包摘要（FR-TENDER-005）。 */
    public Map<String, Object> getFilingPackage(Long announcementId) {
        TenderAnnouncement ann = requireAnn(announcementId);
        if (ann.getFilingPackageJson() == null || ann.getFilingPackageJson().isBlank()) {
            buildFilingPackage(ann);
            ann = requireAnn(announcementId);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(ann.getFilingPackageJson(), Map.class);
            return map;
        } catch (Exception e) {
            return Map.of("raw", ann.getFilingPackageJson());
        }
    }

    private void refundNonWinners(Long announcementId, Long winnerApplicationId, String remark) {
        List<TenderApplication> apps = listApplications(announcementId);
        for (TenderApplication app : apps) {
            if (winnerApplicationId != null && winnerApplicationId.equals(app.getId())) {
                continue;
            }
            refundDeposit(app, remark);
        }
    }

    private void refundDeposit(TenderApplication app, String remark) {
        if (!Boolean.TRUE.equals(app.getDepositPaid())) {
            return;
        }
        if (Boolean.TRUE.equals(app.getDepositRefunded())) {
            return;
        }
        if (app.getDepositAmount() == null || app.getDepositAmount().compareTo(BigDecimal.ZERO) <= 0) {
            app.setDepositRefunded(true);
            app.setDepositRefundedAt(LocalDateTime.now());
            app.setDepositRefundRemark(remark);
            applicationMapper.updateById(app);
            return;
        }
        app.setDepositRefunded(true);
        app.setDepositRefundedAt(LocalDateTime.now());
        app.setDepositRefundRemark(remark);
        applicationMapper.updateById(app);
    }

    private void buildFilingPackage(TenderAnnouncement ann) {
        List<TenderApplication> apps = listApplications(ann.getId());
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("announcementId", ann.getId());
        pack.put("title", ann.getTitle());
        pack.put("assetIds", ann.getAssetIds());
        pack.put("status", ann.getStatus());
        pack.put("result", ann.getResult());
        pack.put("generatedAt", LocalDateTime.now().toString());
        List<Map<String, Object>> appList = new ArrayList<>();
        for (TenderApplication app : apps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("applicationId", app.getId());
            row.put("tenantId", app.getTenantId());
            row.put("auditStatus", app.getAuditStatus());
            row.put("result", app.getResult());
            row.put("depositPaid", app.getDepositPaid());
            row.put("depositAmount", app.getDepositAmount());
            row.put("depositRefunded", app.getDepositRefunded());
            row.put("materialsFileId", app.getMaterialsFileId());
            row.put("auditComment", app.getAuditComment());
            row.put("rankNo", app.getRankNo());
            appList.add(row);
        }
        pack.put("applications", appList);
        try {
            ann.setFilingPackageJson(objectMapper.writeValueAsString(pack));
            announcementMapper.updateById(ann);
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "生成备案包失败");
        }
    }

    private TenderApplication requireApp(Long id) {
        TenderApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "报名不存在");
        }
        return app;
    }

    private TenderAnnouncement requireAnn(Long id) {
        TenderAnnouncement ann = announcementMapper.selectById(id);
        if (ann == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "公告不存在");
        }
        return ann;
    }
}
