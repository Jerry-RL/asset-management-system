package com.ams.modules.asset.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.asset.entity.AssetCertificate;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.mapper.AssetCertificateMapper;
import com.ams.modules.asset.mapper.MortgageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 资债权证（FR-CERT-001）：权证信息、抵押登记/解押（FR-MORT-*）。
 */
@Service
public class CertificateService {

    private final AssetCertificateMapper certificateMapper;
    private final MortgageMapper mortgageMapper;

    public CertificateService(
            AssetCertificateMapper certificateMapper, MortgageMapper mortgageMapper) {
        this.certificateMapper = certificateMapper;
        this.mortgageMapper = mortgageMapper;
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

    public Mortgage registerMortgage(Mortgage mortgage) {
        mortgage.setStatus("active");
        mortgageMapper.insert(mortgage);
        // 权证抵押状态同步
        updateCertMortgageStatus(mortgage.getAssetId(), "mortgaged");
        return mortgage;
    }

    public Mortgage releaseMortgage(Long id) {
        Mortgage mortgage = mortgageMapper.selectById(id);
        if (mortgage == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        mortgage.setStatus("released");
        mortgageMapper.updateById(mortgage);
        updateCertMortgageStatus(mortgage.getAssetId(), "none");
        return mortgage;
    }

    private void updateCertMortgageStatus(Long assetId, String status) {
        List<AssetCertificate> certs = listCertificates(assetId);
        for (AssetCertificate cert : certs) {
            cert.setMortgageStatus(status);
            certificateMapper.updateById(cert);
        }
    }
}
