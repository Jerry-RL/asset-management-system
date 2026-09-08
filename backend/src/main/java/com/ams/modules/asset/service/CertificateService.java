package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.AssetCertificate;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.mapper.AssetCertificateMapper;
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
    private final ApprovalEngine approvalEngine;

    public CertificateService(
            AssetCertificateMapper certificateMapper,
            MortgageMapper mortgageMapper,
            ApprovalEngine approvalEngine) {
        this.certificateMapper = certificateMapper;
        this.mortgageMapper = mortgageMapper;
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

    public List<Mortgage> listMortgages(Long assetId) {
        return mortgageMapper.selectList(
                new LambdaQueryWrapper<Mortgage>()
                        .eq(assetId != null, Mortgage::getAssetId, assetId)
                        .orderByDesc(Mortgage::getId));
    }

    public boolean hasActiveMortgage(Long assetId) {
        if (assetId == null) {
            return false;
        }
        return mortgageMapper.selectCount(
                new LambdaQueryWrapper<Mortgage>()
                        .eq(Mortgage::getAssetId, assetId)
                        .eq(Mortgage::getStatus, "active")) > 0;
    }

    /** 处置/划转前校验（FR-MORT-003）。 */
    public void assertNotMortgaged(Long assetId) {
        if (hasActiveMortgage(assetId)) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "资产处于在押状态，须先解押");
        }
    }

    @Transactional
    public Mortgage registerMortgage(Mortgage mortgage) {
        if (mortgage.getAssetId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "assetId 必填");
        }
        if (mortgage.getMortgagee() == null || mortgage.getMortgagee().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "抵押权利人必填");
        }
        mortgage.setStatus("active");
        mortgage.setCreatedAt(LocalDateTime.now());
        mortgageMapper.insert(mortgage);
        syncCertMortgageStatus(mortgage.getAssetId());
        return mortgage;
    }

    /** 提交解押审批（FR-MORT-003）。 */
    @Transactional
    public ApprovalInstance submitRelease(Long mortgageId, String remark) {
        Mortgage mortgage = require(mortgageId);
        if (!"active".equals(mortgage.getStatus())) {
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

    /** 即将到期抵押清单（供预警扫描）。 */
    public List<Mortgage> listExpiring(int withinDays) {
        java.time.LocalDate limit = java.time.LocalDate.now().plusDays(withinDays);
        return mortgageMapper.selectList(
                new LambdaQueryWrapper<Mortgage>()
                        .eq(Mortgage::getStatus, "active")
                        .le(Mortgage::getEndDate, limit)
                        .ge(Mortgage::getEndDate, java.time.LocalDate.now().minusDays(1)));
    }

    private void doRelease(Mortgage mortgage) {
        mortgage.setStatus("released");
        mortgage.setReleaseStatus("released");
        mortgage.setReleasedAt(LocalDateTime.now());
        mortgage.setUpdatedAt(LocalDateTime.now());
        mortgageMapper.updateById(mortgage);
        syncCertMortgageStatus(mortgage.getAssetId());
    }

    private void syncCertMortgageStatus(Long assetId) {
        String status = hasActiveMortgage(assetId) ? "mortgaged" : "none";
        List<AssetCertificate> certs = listCertificates(assetId);
        for (AssetCertificate cert : certs) {
            cert.setMortgageStatus(status);
            certificateMapper.updateById(cert);
        }
    }

    private Mortgage require(Long id) {
        Mortgage mortgage = mortgageMapper.selectById(id);
        if (mortgage == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "抵押记录不存在");
        }
        return mortgage;
    }
}
