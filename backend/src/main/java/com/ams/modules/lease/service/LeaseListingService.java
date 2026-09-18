package com.ams.modules.lease.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.AssetService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.asset.service.LeaseStatusDeriver;
import com.ams.modules.evaluation.service.EvaluationService;
import com.ams.modules.lease.dto.ImageRef;
import com.ams.modules.lease.dto.LeaseListingDetail;
import com.ams.modules.lease.dto.LeaseListingPublishRequest;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.entity.TenderAnnouncement;
import com.ams.modules.lease.entity.TenderApplication;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.modules.lease.mapper.TenderAnnouncementMapper;
import com.ams.modules.lease.mapper.TenderApplicationMapper;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 招租管理（FR-LEASE-001/002）+ 公开招租、保证金退还与备案（FR-TENDER-*）。
 *
 * <h2>招租发布闭环（V59）</h2>
 * {@code 提交(pending) → 招租发布审批 → 通过(active, 租控派生 leasing, 小程序可见) / 驳回(rejected + 原因)}。
 *
 * <p><b>为什么租控不再走 {@code LeaseControlService.transition}</b>：ADR-0019 之后
 * 「{@code asset.lease_control_status} 由占用与招租事实派生」是唯一口径
 * （{@code v_unit_lease_status} → {@code v_asset_lease_status_derived}），
 * 该视图正是按 {@code lease_listing.status='active' AND asset_unit_id = u.id} 判定 {@code leasing}。
 * 因此通过审批只需把招租置 {@code active} 再 {@link LeaseStatusDeriver#refresh} —— 
 * 直接 transition 会与派生作业互相覆盖。这也要求发布的招租必须挂到**计租单元**上。
 */
@Service
public class LeaseListingService {

    /** 审批业务类型（{@code approval_flow_def.biz_type}，流程定义见 V59）。 */
    private static final String BIZ_TYPE = "lease_listing";

    /** 进行中的招租状态：同一单元不得存在多条（否则会产生重复的发布审批）。 */
    private static final List<String> OPEN_STATUSES =
            List.of(LeaseListing.STATUS_PENDING, LeaseListing.STATUS_ACTIVE);

    private final LeaseListingMapper listingMapper;
    private final TenderAnnouncementMapper announcementMapper;
    private final TenderApplicationMapper applicationMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final AssetUnitService assetUnitService;
    private final LeaseControlService leaseControlService;
    private final LeaseStatusDeriver leaseStatusDeriver;
    private final TenantService tenantService;
    private final UserMapper userMapper;
    private final ApprovalEngine approvalEngine;
    private final ObjectMapper objectMapper;

    public LeaseListingService(
            LeaseListingMapper listingMapper,
            TenderAnnouncementMapper announcementMapper,
            TenderApplicationMapper applicationMapper,
            AssetMapper assetMapper,
            AssetService assetService,
            AssetUnitService assetUnitService,
            LeaseControlService leaseControlService,
            LeaseStatusDeriver leaseStatusDeriver,
            TenantService tenantService,
            UserMapper userMapper,
            ApprovalEngine approvalEngine,
            ObjectMapper objectMapper) {
        this.listingMapper = listingMapper;
        this.announcementMapper = announcementMapper;
        this.applicationMapper = applicationMapper;
        this.assetMapper = assetMapper;
        this.assetService = assetService;
        this.assetUnitService = assetUnitService;
        this.leaseControlService = leaseControlService;
        this.leaseStatusDeriver = leaseStatusDeriver;
        this.tenantService = tenantService;
        this.userMapper = userMapper;
        this.approvalEngine = approvalEngine;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // 招租发布
    // ========================================================================

    /**
     * 招租列表。
     *
     * <p>排序固定为「推荐优先 → 排序号 → id 倒序」：小程序端与后台共用同一口径，
     * 端上不再需要自己排序（索引 {@code idx_lease_listing_pub_sort} 与之一致）。
     *
     * @param status  招租状态（pending / active / rejected / closed），为空表示全部
     * @param assetId 按资产过滤（「招租中」Tab 展开某资产的全部发布审批记录）
     */
    public List<LeaseListing> listListings(String status, Long assetId) {
        List<LeaseListing> rows = listingMapper.selectList(
                new LambdaQueryWrapper<LeaseListing>()
                        .eq(status != null && !status.isBlank(), LeaseListing::getStatus, status)
                        .eq(assetId != null, LeaseListing::getAssetId, assetId)
                        .orderByDesc(LeaseListing::getRecommended)
                        .orderByAsc(LeaseListing::getSortNo)
                        .orderByDesc(LeaseListing::getId));
        hydrate(rows);
        return rows;
    }

    /**
     * 发布招租 = 提交审批（V59）。
     *
     * <p>提交后招租为 {@code pending}，租控**不变**（仍是 vacant），小程序端不可见 ——
     * 「资产变为招租中」与「小程序可见」都只发生在审批通过那一刻。
     */
    @Transactional
    public LeaseListing submit(LeaseListingPublishRequest request) {
        Asset asset = requireAsset(request.getAssetId());
        // 仅空置资产可发起（与自用/占用同口径：招租也是「从空置出发」的动作）
        leaseControlService.assertVacant(asset.getId());

        // 招租必须落到计租单元上：租控视图按 asset_unit_id 判定 leasing（ADR-0019）
        AssetUnit unit = assetUnitService.resolveForLease(asset.getId(), request.getAssetUnitId());
        assertNoOpenListing(unit.getId());

        LeaseListing listing = new LeaseListing();
        listing.setAssetId(asset.getId());
        listing.setAssetUnitId(unit.getId());
        applyPublishFields(listing, asset, request);

        listing.setStatus(LeaseListing.STATUS_PENDING);
        listing.setRejectReason(null);
        listing.setPublishedAt(null);
        listing.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        listing.setCreatedAt(LocalDateTime.now());
        listingMapper.insert(listing);

        approvalEngine.start(BIZ_TYPE, listing.getId());
        hydrate(List.of(listing));
        return listing;
    }

    /**
     * 重新提交（驳回后修改再报）。
     *
     * <p>不复用 {@link #submit}：资产与单元已定，重报只改表单字段，
     * 再走一次 {@code assertVacant} 会在「驳回后资产被别处占用」时给出误导性的报错。
     */
    @Transactional
    public LeaseListing resubmit(Long id, LeaseListingPublishRequest request) {
        LeaseListing listing = requireListing(id);
        if (!LeaseListing.STATUS_REJECTED.equals(listing.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅已驳回的招租可重新提交");
        }
        Asset asset = requireAsset(listing.getAssetId());
        applyPublishFields(listing, asset, request);

        listing.setStatus(LeaseListing.STATUS_PENDING);
        listing.setRejectReason(null);
        listing.setUpdatedAt(LocalDateTime.now());
        listingMapper.updateById(listing);

        approvalEngine.start(BIZ_TYPE, listing.getId());
        hydrate(List.of(listing));
        return listing;
    }

    /** 关闭招租：人工结束。租控随之回落（无生效占用且无 active 招租 → vacant）。 */
    @Transactional
    public LeaseListing close(Long id) {
        LeaseListing listing = requireListing(id);
        if (LeaseListing.STATUS_CLOSED.equals(listing.getStatus())) {
            return listing;
        }
        listing.setStatus(LeaseListing.STATUS_CLOSED);
        listing.setClosedAt(LocalDateTime.now());
        listing.setUpdatedAt(LocalDateTime.now());
        listingMapper.updateById(listing);
        leaseStatusDeriver.refresh(listing.getAssetId());
        hydrate(List.of(listing));
        return listing;
    }

    /** 招租详情：招租字段 + 资产详情 + 发起人信息（仅查看详情时可见）。 */
    public LeaseListingDetail detail(Long id) {
        LeaseListing listing = requireListing(id);
        hydrate(List.of(listing));

        LeaseListingDetail detail = new LeaseListingDetail();
        detail.setListing(listing);
        detail.setAsset(assetService.getAsset(listing.getAssetId()));

        if (listing.getCreatedBy() != null) {
            User creator = userMapper.selectById(listing.getCreatedBy());
            if (creator != null) {
                detail.setCreatedByName(creator.getName());
                detail.setCreatedByPhone(creator.getPhone());
            }
        }
        return detail;
    }

    /**
     * 审批完成回调：通过则发布（active + 租控派生），驳回则落原因。
     *
     * <p>幂等：只处理仍是 {@code pending} 的行。发件箱兜底重放与「人工先改了状态」两种情况
     * 都会在这里成为空操作。
     */
    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (!BIZ_TYPE.equals(event.getBizType())) {
            return;
        }
        LeaseListing listing = listingMapper.selectById(event.getBizId());
        if (listing == null || !LeaseListing.STATUS_PENDING.equals(listing.getStatus())) {
            return;
        }
        if (event.isApproved()) {
            listing.setStatus(LeaseListing.STATUS_ACTIVE);
            listing.setPublishedAt(LocalDateTime.now());
            listing.setRejectReason(null);
        } else {
            listing.setStatus(LeaseListing.STATUS_REJECTED);
            // 驳回原因来自审批意见（V59 起由 ApprovalCompletedEvent 携带，
            // 此前只落在 approval_task.comment，业务侧拿不到）
            listing.setRejectReason(event.getComment());
        }
        listing.setUpdatedAt(LocalDateTime.now());
        listingMapper.updateById(listing);
        // 通过 → 视图派生为 leasing；驳回 → 仍 vacant（无 active 招租）
        leaseStatusDeriver.refresh(listing.getAssetId());
    }

    // ========================================================================
    // 公开招租 / 报名 / 保证金 / 备案（FR-TENDER-*）
    // ========================================================================

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

    // ========================================================================
    // 内部
    // ========================================================================

    /**
     * 把请求字段搬到实体上（提交与重报共用）。
     *
     * <p><b>年租金 → 月租金的换算</b>：表单只收「年租金」，而 {@code rent_amount} 是
     * 「元/月」口径的既有列 —— 小程序按 {@code ¥x/月} 展示、底价校验也按月租金比对，
     * 直接写入年租金会在这两处产生 12 倍的量纲错误。因此这里写入月均额，
     * 底价校验改用「年化底价」比对（见下）。
     */
    private void applyPublishFields(LeaseListing listing, Asset asset, LeaseListingPublishRequest req) {
        if (req.getAnnualRent() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "年租金不能为空");
        }
        assertNotBelowFloor(asset, req.getAnnualRent(), req.getRentNegotiable());

        listing.setAnnualRent(req.getAnnualRent());
        listing.setRentAmount(req.getAnnualRent()
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
        listing.setRentType(req.getRentType());
        listing.setRentNegotiable(Boolean.TRUE.equals(req.getRentNegotiable()));
        listing.setRecommended(Boolean.TRUE.equals(req.getRecommended()));
        listing.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        listing.setIntro(req.getIntro());
        listing.setRemark(req.getRemark());

        ImageRef cover = req.getCoverImage();
        listing.setCoverImageFileId(cover == null ? null : cover.getFileId());
        listing.setCoverImageUrl(cover == null ? null : cover.getUrl());
        listing.setDetailImagesJson(writeImages(req.getDetailImages()));
        listing.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 底价校验（FR-EVAL-004）：{@code effectiveFloor} 是**月**口径的评估 / 备案底价，
     * 而表单收的是年租金，故按年化后比对 —— 这样 1 月租金底价 ↔ 12 月租金的年租金
     * 才是同一量纲，否则「年租金 < 月底价」几乎永不成立，校验形同虚设。
     */
    private void assertNotBelowFloor(Asset asset, BigDecimal annualRent, Boolean rentNegotiable) {
        BigDecimal floor = EvaluationService.effectiveFloor(asset);
        if (floor == null || Boolean.TRUE.equals(rentNegotiable)) {
            return;
        }
        BigDecimal annualFloor = floor.multiply(BigDecimal.valueOf(12));
        if (annualRent.compareTo(annualFloor) < 0) {
            throw new AppException(ErrorCode.CONFLICT,
                    "年租金低于评估/备案底价年化额（" + annualFloor.toPlainString()
                            + " 元），请调高租金或标记可议价后特批");
        }
    }

    /** 同一计租单元不得有进行中的招租（pending / active），否则会产生重复的发布审批。 */
    private void assertNoOpenListing(Long unitId) {
        Long exists = listingMapper.selectCount(new LambdaQueryWrapper<LeaseListing>()
                .eq(LeaseListing::getAssetUnitId, unitId)
                .in(LeaseListing::getStatus, OPEN_STATUSES));
        if (exists != null && exists > 0) {
            throw new AppException(ErrorCode.CONFLICT, "该资产已有进行中的招租，请先关闭或等待审批");
        }
    }

    /** 回填解析后的详情图与资产编号 / 名称（列表与详情共用）。 */
    private void hydrate(List<LeaseListing> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> assetIds = new LinkedHashSet<>();
        for (LeaseListing row : rows) {
            row.setDetailImages(readImages(row.getDetailImagesJson()));
            if (row.getAssetId() != null) {
                assetIds.add(row.getAssetId());
            }
        }
        if (assetIds.isEmpty()) {
            return;
        }
        Map<Long, Asset> assets = assetMapper.selectBatchIds(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, asset -> asset, (a, b) -> a));
        for (LeaseListing row : rows) {
            Asset asset = assets.get(row.getAssetId());
            if (asset != null) {
                row.setAssetNo(asset.getAssetNo());
                row.setAssetName(asset.getName());
            }
        }
    }

    private List<ImageRef> readImages(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<ImageRef> list = objectMapper.readValue(json, new TypeReference<List<ImageRef>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            // 脏数据不阻断列表：解析失败按「无详情图」处理，避免一张坏行让整个列表 500
            return new ArrayList<>();
        }
    }

    private String writeImages(List<ImageRef> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        List<ImageRef> cleaned = images.stream()
                .filter(Objects::nonNull)
                .filter(img -> img.getFileId() != null || (img.getUrl() != null && !img.getUrl().isBlank()))
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new AppException(ErrorCode.BAD_REQUEST, "详情列表图格式不正确");
        }
    }

    private Asset requireAsset(Long assetId) {
        if (assetId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "资产不能为空");
        }
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        return asset;
    }

    private LeaseListing requireListing(Long id) {
        LeaseListing listing = id == null ? null : listingMapper.selectById(id);
        if (listing == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "招租记录不存在");
        }
        return listing;
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
