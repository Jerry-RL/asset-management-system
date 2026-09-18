package com.ams.modules.lease.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.service.AssetService;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.modules.asset.service.LeaseControlService;
import com.ams.modules.asset.service.LeaseStatusDeriver;
import com.ams.modules.lease.dto.ImageRef;
import com.ams.modules.lease.dto.LeaseListingPublishRequest;
import com.ams.modules.lease.entity.LeaseListing;
import com.ams.modules.lease.mapper.LeaseListingMapper;
import com.ams.modules.lease.mapper.TenderAnnouncementMapper;
import com.ams.modules.lease.mapper.TenderApplicationMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 招租发布审批闭环（V59）：提交 → 审批通过 / 驳回。
 *
 * <p>钉死四条**在页面上看不出来、但错一次就长期错**的规则：
 *
 * <ol>
 *   <li>提交只落 {@code pending}，**不得**直接发布 —— 直接置 active 等于绕过审批，
 *       且会让资产立刻变「招租中」、小程序立刻可见；</li>
 *   <li>年租金是表单口径、{@code rent_amount} 是月均口径，换算写错就是 12 倍的量纲错误；</li>
 *   <li>驳回原因必须落到 {@code reject_reason}（此前只存在 {@code approval_task.comment}，
 *       业务侧拿不到，「驳回填写原因」这条需求在界面上是空的）；</li>
 *   <li>监听器幂等：非 {@code pending} 的行不写库、不刷租控 —— 发件箱兜底重放会重复投递。</li>
 * </ol>
 *
 * <p>租控状态由 {@code LeaseStatusDeriver} 派生（ADR-0019），因此这里验证的是
 * 「调用了 refresh」，而不是「写了 asset.lease_control_status」——后者已不是合法写入口。
 */
class LeaseListingPublishApprovalTest {

    private static final long ASSET_ID = 11L;
    private static final long UNIT_ID = 22L;
    private static final long LISTING_ID = 33L;
    private static final String BIZ_TYPE = "lease_listing";

    private final LeaseListingMapper listingMapper = mock(LeaseListingMapper.class);
    private final AssetMapper assetMapper = mock(AssetMapper.class);
    private final AssetService assetService = mock(AssetService.class);
    private final AssetUnitService assetUnitService = mock(AssetUnitService.class);
    private final LeaseControlService leaseControlService = mock(LeaseControlService.class);
    private final LeaseStatusDeriver leaseStatusDeriver = mock(LeaseStatusDeriver.class);
    private final ApprovalEngine approvalEngine = mock(ApprovalEngine.class);
    private final UserMapper userMapper = mock(UserMapper.class);

    /** 用真实 ObjectMapper：详情图的 JSON 读写正是被测行为的一部分，桩掉会掩盖序列化错误。 */
    private final LeaseListingService service = new LeaseListingService(
            listingMapper,
            mock(TenderAnnouncementMapper.class),
            mock(TenderApplicationMapper.class),
            assetMapper,
            assetService,
            assetUnitService,
            leaseControlService,
            leaseStatusDeriver,
            mock(TenantService.class),
            userMapper,
            approvalEngine,
            new ObjectMapper());

    private Asset vacantAsset(String baseRentFloor) {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setBaseRentFloor(baseRentFloor == null ? null : new BigDecimal(baseRentFloor));
        return asset;
    }

    private LeaseListingPublishRequest request(String annualRent, Boolean negotiable) {
        LeaseListingPublishRequest req = new LeaseListingPublishRequest();
        req.setAssetId(ASSET_ID);
        req.setAnnualRent(new BigDecimal(annualRent));
        req.setRentType("fixed_yearly");
        req.setRentNegotiable(negotiable);
        req.setRecommended(true);
        req.setSortNo(5);
        req.setIntro("临街旺铺");
        req.setCoverImage(new ImageRef(1L, "/api/v1/files/object/cover.png"));
        req.setDetailImages(List.of(new ImageRef(2L, "/api/v1/files/object/d1.png")));
        return req;
    }

    /** 桩：资产存在、单元可租、当前没有进行中的招租、insert 后回填主键。 */
    private void stubHappyPath(String baseRentFloor) {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(vacantAsset(baseRentFloor));
        AssetUnit unit = new AssetUnit();
        unit.setId(UNIT_ID);
        unit.setAssetId(ASSET_ID);
        when(assetUnitService.resolveForLease(eq(ASSET_ID), any())).thenReturn(unit);
        when(listingMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            ((LeaseListing) inv.getArgument(0)).setId(LISTING_ID);
            return 1;
        }).when(listingMapper).insert(any(LeaseListing.class));
    }

    @Test
    @DisplayName("提交只落 pending：不写 publishedAt、不刷租控，并启动招租发布审批")
    void submitStaysPendingUntilApproved() {
        stubHappyPath("1000.00");

        LeaseListing listing = service.submit(request("24000.00", false));

        assertThat(listing.getStatus()).isEqualTo(LeaseListing.STATUS_PENDING);
        assertThat(listing.getPublishedAt()).isNull();
        assertThat(listing.getRejectReason()).isNull();
        assertThat(listing.getAssetUnitId()).isEqualTo(UNIT_ID);
        verify(leaseControlService).assertVacant(ASSET_ID);
        verify(approvalEngine).start(BIZ_TYPE, LISTING_ID);
        // 未审批通过就刷租控 = 资产提前变「招租中」，与「通过后才可见」矛盾
        verify(leaseStatusDeriver, never()).refresh(anyLong());
    }

    @Test
    @DisplayName("年租金 → 月均 rent_amount 换算（12 倍量纲不能错），详情图落 JSON")
    void submitConvertsAnnualRentToMonthly() {
        stubHappyPath(null);

        LeaseListing listing = service.submit(request("24000.00", false));

        assertThat(listing.getAnnualRent()).isEqualByComparingTo("24000.00");
        assertThat(listing.getRentAmount()).isEqualByComparingTo("2000.00");
        assertThat(listing.getDetailImagesJson()).contains("d1.png");
        assertThat(listing.getDetailImages()).hasSize(1);
        assertThat(listing.getCoverImageUrl()).isEqualTo("/api/v1/files/object/cover.png");
    }

    @Test
    @DisplayName("低于底价年化额且未标记可议价 → 冲突，且不落库")
    void submitRejectsBelowFloor() {
        stubHappyPath("1000.00"); // 月底价 1000 → 年化 12000

        assertThatThrownBy(() -> service.submit(request("11000.00", false)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("低于评估/备案底价");

        verify(listingMapper, never()).insert(any(LeaseListing.class));
        verify(approvalEngine, never()).start(any(), anyLong());
    }

    @Test
    @DisplayName("低于底价但标记可议价 → 放行（特批口径）")
    void submitAllowsBelowFloorWhenNegotiable() {
        stubHappyPath("1000.00");

        LeaseListing listing = service.submit(request("11000.00", true));

        assertThat(listing.getStatus()).isEqualTo(LeaseListing.STATUS_PENDING);
    }

    @Test
    @DisplayName("同一单元已有进行中的招租 → 冲突，避免产生重复的发布审批")
    void submitRejectsDuplicateOpenListing() {
        stubHappyPath(null);
        when(listingMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.submit(request("24000.00", false)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已有进行中的招租");

        verify(approvalEngine, never()).start(any(), anyLong());
    }

    @Test
    @DisplayName("审批通过 → active + publishedAt，并刷新租控（视图派生为 leasing）")
    void approvePublishesListing() {
        LeaseListing pending = new LeaseListing();
        pending.setId(LISTING_ID);
        pending.setAssetId(ASSET_ID);
        pending.setStatus(LeaseListing.STATUS_PENDING);
        when(listingMapper.selectById(LISTING_ID)).thenReturn(pending);

        service.onApprovalCompleted(new ApprovalCompletedEvent(BIZ_TYPE, LISTING_ID, true, "同意"));

        ArgumentCaptor<LeaseListing> saved = ArgumentCaptor.forClass(LeaseListing.class);
        verify(listingMapper).updateById(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(LeaseListing.STATUS_ACTIVE);
        assertThat(saved.getValue().getPublishedAt()).isNotNull();
        assertThat(saved.getValue().getRejectReason()).isNull();
        verify(leaseStatusDeriver).refresh(ASSET_ID);
    }

    @Test
    @DisplayName("审批驳回 → rejected + 驳回原因落库（原因来自审批意见）")
    void rejectStoresReason() {
        LeaseListing pending = new LeaseListing();
        pending.setId(LISTING_ID);
        pending.setAssetId(ASSET_ID);
        pending.setStatus(LeaseListing.STATUS_PENDING);
        when(listingMapper.selectById(LISTING_ID)).thenReturn(pending);

        service.onApprovalCompleted(
                new ApprovalCompletedEvent(BIZ_TYPE, LISTING_ID, false, "图片与实景不符，请重拍"));

        ArgumentCaptor<LeaseListing> saved = ArgumentCaptor.forClass(LeaseListing.class);
        verify(listingMapper).updateById(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(LeaseListing.STATUS_REJECTED);
        assertThat(saved.getValue().getRejectReason()).isEqualTo("图片与实景不符，请重拍");
        // 驳回后仍是空置：不能因为「提交过招租」就让资产停在招租中
        verify(leaseStatusDeriver).refresh(ASSET_ID);
    }

    @Test
    @DisplayName("监听器幂等：非 pending 的行不写库、不刷租控（发件箱重放会重复投递）")
    void listenerIgnoresNonPending() {
        LeaseListing active = new LeaseListing();
        active.setId(LISTING_ID);
        active.setAssetId(ASSET_ID);
        active.setStatus(LeaseListing.STATUS_ACTIVE);
        when(listingMapper.selectById(LISTING_ID)).thenReturn(active);

        service.onApprovalCompleted(new ApprovalCompletedEvent(BIZ_TYPE, LISTING_ID, false, "撤回"));

        verify(listingMapper, never()).updateById(any(LeaseListing.class));
        verify(leaseStatusDeriver, never()).refresh(anyLong());
    }

    @Test
    @DisplayName("监听器只处理招租发布：其他 bizType 的事件原样忽略")
    void listenerIgnoresOtherBizTypes() {
        service.onApprovalCompleted(new ApprovalCompletedEvent("contract", LISTING_ID, true, null));

        verify(listingMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("重新提交：仅 rejected 可重报，重报后回到 pending 并清空驳回原因")
    void resubmitOnlyFromRejected() {
        stubHappyPath(null);
        LeaseListing rejected = new LeaseListing();
        rejected.setId(LISTING_ID);
        rejected.setAssetId(ASSET_ID);
        rejected.setStatus(LeaseListing.STATUS_REJECTED);
        rejected.setRejectReason("金额偏低");
        when(listingMapper.selectById(LISTING_ID)).thenReturn(rejected);

        LeaseListing listing = service.resubmit(LISTING_ID, request("24000.00", true));

        assertThat(listing.getStatus()).isEqualTo(LeaseListing.STATUS_PENDING);
        assertThat(listing.getRejectReason()).isNull();
        verify(listingMapper).updateById(rejected);
        verify(approvalEngine).start(BIZ_TYPE, LISTING_ID);
    }

    @Test
    @DisplayName("重新提交：已发布的招租不可重报（否则等于绕过审批重新发布）")
    void resubmitRejectsNonRejected() {
        LeaseListing active = new LeaseListing();
        active.setId(LISTING_ID);
        active.setStatus(LeaseListing.STATUS_ACTIVE);
        when(listingMapper.selectById(LISTING_ID)).thenReturn(active);

        assertThatThrownBy(() -> service.resubmit(LISTING_ID, request("24000.00", true)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("仅已驳回");

        verify(approvalEngine, never()).start(any(), anyLong());
    }

    @Test
    @DisplayName("导出详情：带资产详情与发起人信息（仅查看详情时可见）")
    void detailCarriesAssetAndCreator() {
        LeaseListing active = new LeaseListing();
        active.setId(LISTING_ID);
        active.setAssetId(ASSET_ID);
        active.setCreatedBy(9L);
        active.setStatus(LeaseListing.STATUS_ACTIVE);
        when(listingMapper.selectById(LISTING_ID)).thenReturn(active);

        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setAssetNo("ZC-001");
        when(assetService.getAsset(ASSET_ID)).thenReturn(asset);

        com.ams.modules.org.entity.User user = new com.ams.modules.org.entity.User();
        user.setId(9L);
        user.setName("张三");
        user.setPhone("13800000000");
        when(userMapper.selectById(9L)).thenReturn(user);

        com.ams.modules.lease.dto.LeaseListingDetail detail = service.detail(LISTING_ID);

        assertThat(detail.getAsset().getAssetNo()).isEqualTo("ZC-001");
        assertThat(detail.getCreatedByName()).isEqualTo("张三");
        assertThat(detail.getCreatedByPhone()).isEqualTo("13800000000");
    }

    /** 详情里发起人缺失时不得抛错（历史数据 / 账号被删）。 */
    @Test
    @DisplayName("导出详情：发起人不存在时留空而不是 500")
    void detailToleratesMissingCreator() {
        LeaseListing listing = new LeaseListing();
        listing.setId(LISTING_ID);
        listing.setAssetId(ASSET_ID);
        listing.setCreatedBy(404L);
        listing.setStatus(LeaseListing.STATUS_ACTIVE);
        when(listingMapper.selectById(LISTING_ID)).thenReturn(listing);
        when(assetService.getAsset(ASSET_ID)).thenReturn(new Asset());
        when(userMapper.selectById(404L)).thenReturn(null);

        assertThat(Optional.ofNullable(service.detail(LISTING_ID).getCreatedByName())).isEmpty();
    }
}
