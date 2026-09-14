package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.entity.AssetCertificate;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.mapper.AssetCertificateMapper;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.asset.mapper.MortgageMapper;
import com.ams.platform.approval.ApprovalEngine;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.event.ApprovalCompletedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资债权证（FR-CERT-001）+ 抵押登记/解押（FR-MORT-*）。
 */
@Service
public class CertificateService {

    private final AssetCertificateMapper certificateMapper;
    private final MortgageMapper mortgageMapper;
    private final AssetMapper assetMapper;
    private final ApprovalEngine approvalEngine;

    public CertificateService(
            AssetCertificateMapper certificateMapper,
            MortgageMapper mortgageMapper,
            AssetMapper assetMapper,
            ApprovalEngine approvalEngine) {
        this.certificateMapper = certificateMapper;
        this.mortgageMapper = mortgageMapper;
        this.assetMapper = assetMapper;
        this.approvalEngine = approvalEngine;
    }

    public List<AssetCertificate> listCertificates(Long assetId) {
        return certificateMapper.selectList(
                new LambdaQueryWrapper<AssetCertificate>()
                        .eq(assetId != null, AssetCertificate::getAssetId, assetId)
                        .orderByDesc(AssetCertificate::getId));
    }

    public AssetCertificate createCertificate(AssetCertificate certificate) {
        certificateMapper.insert(certificate);
        return certificate;
    }

    /**
     * 按资产列抵押。{@code assetId} 为 null 时返回全部未软删的抵押记录。
     *
     * <p>{@code assetId} 非 null 时走**覆盖查询**（自身 / 所属分区 / 所属项目 三级），
     * 而不是只按 {@code asset_id} 反查：项目或分区被抵押时，其下的资产同样在押，
     * 只列资产级的话界面上会显示「未抵押」，而处置时又被拦下。
     */
    public List<Mortgage> listMortgages(Long assetId) {
        if (assetId != null) {
            return mortgageMapper.selectCoveringAsset(assetId);
        }
        return mortgageMapper.selectList(
                new LambdaQueryWrapper<Mortgage>()
                        .isNull(Mortgage::getDeletedAt)
                        .orderByDesc(Mortgage::getId));
    }

    /**
     * 该资产是否在押（自身 / 所属分区 / 所属项目 任一层级的在押记录）。
     *
     * <p>草稿不算在押：它是未生效的 working copy，把草稿算进来会让「先起草再补材料」
     * 这个正常流程变成「资产被冻结」。
     */
    public boolean hasActiveMortgage(Long assetId) {
        if (assetId == null) {
            return false;
        }
        return mortgageMapper.countActiveCoveringAsset(assetId) > 0;
    }

    /** 处置/划转前校验（FR-MORT-003）。 */
    public void assertNotMortgaged(Long assetId) {
        if (hasActiveMortgage(assetId)) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "资产处于在押状态，须先解押");
        }
    }

    /**
     * 重算「该标的覆盖的资产」的权证抵押状态。
     *
     * <p>投影方向是「先改抵押、再调本方法」，而不是让权证状态由抵押状态派生查询：
     * 权证状态会出现在列表与导出里，逐行现算会让那些查询退化成 N+1 子查询。
     */
    public void refreshCertMortgageStatus(String targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            return;
        }
        mortgageMapper.refreshCertMortgageStatus(targetType, targetId);
    }

    /**
     * 登记抵押（legacy 直连入口，不经草稿）。
     *
     * <p>{@code target_type} 固定为 {@code asset}：本入口的语义就是「给这个资产挂一笔抵押」。
     * 三级标的（项目 / 分区）请走 {@code MortgageRecordService} 的草稿 → 生效流程。
     */
    @Transactional
    public Mortgage registerMortgage(Mortgage mortgage) {
        if (mortgage.getAssetId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "assetId 必填");
        }
        if (mortgage.getMortgagee() == null || mortgage.getMortgagee().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押权利人必填");
        }
        Asset asset = assetMapper.selectById(mortgage.getAssetId());
        if (asset == null || asset.getDeletedAt() != null) {
            throw new AppException(ErrorCode.NOT_FOUND, "资产不存在或已删除：" + mortgage.getAssetId());
        }
        // 资产级抵押下两个标的列必须同值：只写一个会让「按 target 查」与「按 asset 查」
        // 两条路径（在押校验用前者、档案反查用后者）给出不同结果
        mortgage.setTargetType(Mortgage.TARGET_ASSET);
        mortgage.setTargetId(mortgage.getAssetId());
        if (mortgage.getCompanyId() == null) {
            mortgage.setCompanyId(asset.getAssetCompanyId());
        }
        mortgage.setStatus(Mortgage.STATUS_ACTIVE);
        mortgage.setCreatedAt(LocalDateTime.now());
        mortgageMapper.insert(mortgage);
        syncCertMortgageStatus(mortgage.getAssetId());
        return mortgage;
    }

    /** 提交解押审批（FR-MORT-003）。 */
    @Transactional
    public ApprovalInstance submitRelease(Long mortgageId, String remark) {
        Mortgage mortgage = require(mortgageId);
        if (Mortgage.STATUS_DRAFT.equals(mortgage.getStatus())) {
            // 与「已解押」分开报：草稿是「还没生效」，让它去走解押流程会得到一句
            // 「仅在押抵押可申请解押」，用户无法判断到底缺哪一步
            throw new AppException(ErrorCode.CONFLICT, "草稿尚未生效，请先生效再申请解押");
        }
        if (!Mortgage.STATUS_ACTIVE.equals(mortgage.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "仅在押抵押可申请解押");
        }
        if ("releasing".equals(mortgage.getReleaseStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "解押审批进行中");
        }
        mortgage.setReleaseStatus("releasing");
        mortgage.setReleaseRemark(remark);
        mortgage.setUpdatedAt(LocalDateTime.now());
        mortgageMapper.updateById(mortgage);
        return approvalEngine.start("mortgage_release", mortgageId);
    }

    /** 兼容旧接口：直接解押（无审批时用于管理员强制）。 */
    @Transactional
    public Mortgage releaseMortgage(Long id) {
        Mortgage mortgage = require(id);
        doRelease(mortgage);
        return mortgage;
    }

    @EventListener
    @Transactional
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (!"mortgage_release".equals(event.getBizType())) {
            return;
        }
        Mortgage mortgage = mortgageMapper.selectById(event.getBizId());
        if (mortgage == null || !"releasing".equals(mortgage.getReleaseStatus())) {
            return;
        }
        if (event.isApproved()) {
            doRelease(mortgage);
        } else {
            mortgage.setReleaseStatus("rejected");
            mortgage.setUpdatedAt(LocalDateTime.now());
            mortgageMapper.updateById(mortgage);
        }
    }

    /** 即将到期抵押清单（供预警扫描）。草稿与已软删的行不参与。 */
    public List<Mortgage> listExpiring(int withinDays) {
        java.time.LocalDate limit = java.time.LocalDate.now().plusDays(withinDays);
        return mortgageMapper.selectList(
                new LambdaQueryWrapper<Mortgage>()
                        .eq(Mortgage::getStatus, Mortgage.STATUS_ACTIVE)
                        .isNull(Mortgage::getDeletedAt)
                        .le(Mortgage::getEndDate, limit)
                        .ge(Mortgage::getEndDate, java.time.LocalDate.now().minusDays(1)));
    }

    private void doRelease(Mortgage mortgage) {
        mortgage.setStatus(Mortgage.STATUS_RELEASED);
        mortgage.setReleaseStatus("released");
        mortgage.setReleasedAt(LocalDateTime.now());
        mortgage.setUpdatedAt(LocalDateTime.now());
        mortgageMapper.updateById(mortgage);
        // 按**记录自己的标的**重算，而不是按 assetId：项目 / 分区级抵押的 assetId 是 NULL，
        // 而且它的解押要一次性把该项目 / 分区下所有资产的权证状态重新判定
        refreshCertMortgageStatus(mortgage.getTargetType(), mortgage.getTargetId());
    }

    /**
     * 同步某资产的权证抵押状态。等价于「按 asset 标的重算」，保留这个名字是因为
     * 两处既有调用（登记 / 解押）都是资产级操作，读起来更直白。
     */
    private void syncCertMortgageStatus(Long assetId) {
        if (assetId == null) {
            return;
        }
        refreshCertMortgageStatus(Mortgage.TARGET_ASSET, assetId);
    }

    /** 取一条未软删的抵押记录。软删的行必须当作不存在，否则已删草稿仍能走解押。 */
    private Mortgage require(Long id) {
        Mortgage mortgage = mortgageMapper.selectById(id);
        if (mortgage == null || mortgage.getDeletedAt() != null) {
            throw new AppException(ErrorCode.NOT_FOUND, "抵押记录不存在");
        }
        return mortgage;
    }
}
