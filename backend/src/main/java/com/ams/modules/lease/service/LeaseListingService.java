package com.ams.modules.lease.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.LeaseControlStatus;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.entity.TenderAnnouncement;
import com.ams.modules.lease.entity.TenderApplication;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.modules.lease.mapper.TenderAnnouncementMapper;
import com.ams.modules.lease.mapper.TenderApplicationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 招租管理（FR-LEASE-001/002）+ 公开招租与流标弃标（FR-TENDER-*）。
 */
@Service
public class LeaseListingService {

    private final LeaseListingMapper listingMapper;
    private final TenderAnnouncementMapper announcementMapper;
    private final TenderApplicationMapper applicationMapper;
    private final AssetMapper assetMapper;
    private final LeaseControlService leaseControlService;

    public LeaseListingService(
            LeaseListingMapper listingMapper,
            TenderAnnouncementMapper announcementMapper,
            TenderApplicationMapper applicationMapper,
            AssetMapper assetMapper,
            LeaseControlService leaseControlService) {
        this.listingMapper = listingMapper;
        this.announcementMapper = announcementMapper;
        this.applicationMapper = applicationMapper;
        this.assetMapper = assetMapper;
        this.leaseControlService = leaseControlService;
    }

    // ---- 招租发布 ----
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
        // 仅空置资产可发布招租
        leaseControlService.assertVacant(asset.getId());
        listing.setStatus("active");
        listing.setPublishedAt(LocalDateTime.now());
        listingMapper.insert(listing);
        // 租控 → 招租中
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

    // ---- 公开招租公告 ----
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

    // ---- 报名受理 ----
    public TenderApplication apply(Long announcementId, Long tenantId, Boolean depositPaid,
            java.math.BigDecimal depositAmount) {
        TenderApplication app = new TenderApplication();
        app.setAnnouncementId(announcementId);
        app.setTenantId(tenantId);
        app.setAuditStatus("pending");
        app.setDepositPaid(depositPaid != null && depositPaid);
        app.setDepositAmount(depositAmount);
        app.setDepositRefunded(false);
        applicationMapper.insert(app);
        return app;
    }

    public List<TenderApplication> listApplications(Long announcementId) {
        return applicationMapper.selectList(
                new LambdaQueryWrapper<TenderApplication>()
                        .eq(TenderApplication::getAnnouncementId, announcementId)
                        .orderByAsc(TenderApplication::getId));
    }

    /** 资格审查（FR-TENDER-004）：符合/不符合 + 意见留痕。 */
    public TenderApplication audit(Long applicationId, boolean approved, String comment, Integer rankNo) {
        TenderApplication app = applicationMapper.selectById(applicationId);
        if (app == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        app.setAuditStatus(approved ? "approved" : "rejected");
        app.setAuditComment(comment);
        app.setRankNo(rankNo);
        applicationMapper.updateById(app);
        return app;
    }

    /** 流标判定（FR-TENDER-007）：报名不足或均不符合。 */
    public TenderAnnouncement markFlowed(Long announcementId) {
        TenderAnnouncement ann = announcementMapper.selectById(announcementId);
        if (ann == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        ann.setStatus("flowed");
        ann.setResult("流标");
        announcementMapper.updateById(ann);
        return ann;
    }

    /** 中标结果与归档（FR-TENDER-005）。 */
    public TenderAnnouncement finalizeResult(Long announcementId, Long winnerApplicationId) {
        TenderAnnouncement ann = announcementMapper.selectById(announcementId);
        if (ann == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        TenderApplication winner = applicationMapper.selectById(winnerApplicationId);
        if (winner != null) {
            winner.setResult("won");
            applicationMapper.updateById(winner);
        }
        ann.setStatus("closed");
        ann.setResult("中标租户 " + (winner == null ? "" : winner.getTenantId()));
        announcementMapper.updateById(ann);
        return ann;
    }
}
