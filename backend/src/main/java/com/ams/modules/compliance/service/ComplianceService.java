package com.ams.modules.compliance.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.compliance.entity.ComplianceFiling;
import com.ams.modules.compliance.mapper.ComplianceFilingMapper;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.lease.entity.TenderAnnouncement;
import com.ams.modules.lease.mapper.TenderAnnouncementMapper;
import com.ams.modules.lease.service.LeaseListingService;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合规备案材料包（FR-COMP）：招租/合同/处置等业务统一归档摘要。
 */
@Service
public class ComplianceService {

    private final ComplianceFilingMapper filingMapper;
    private final LeaseListingService leaseListingService;
    private final TenderAnnouncementMapper announcementMapper;
    private final ContractMapper contractMapper;
    private final ObjectMapper objectMapper;

    public ComplianceService(
            ComplianceFilingMapper filingMapper,
            LeaseListingService leaseListingService,
            TenderAnnouncementMapper announcementMapper,
            ContractMapper contractMapper,
            ObjectMapper objectMapper) {
        this.filingMapper = filingMapper;
        this.leaseListingService = leaseListingService;
        this.announcementMapper = announcementMapper;
        this.contractMapper = contractMapper;
        this.objectMapper = objectMapper;
    }

    public List<ComplianceFiling> list(String bizType, Long bizId) {
        return filingMapper.selectList(
                new LambdaQueryWrapper<ComplianceFiling>()
                        .eq(bizType != null, ComplianceFiling::getBizType, bizType)
                        .eq(bizId != null, ComplianceFiling::getBizId, bizId)
                        .orderByDesc(ComplianceFiling::getId));
    }

    public ComplianceFiling get(Long id) {
        ComplianceFiling filing = filingMapper.selectById(id);
        if (filing == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return filing;
    }

    @Transactional
    public ComplianceFiling build(String bizType, Long bizId, boolean needMeeting) {
        if (bizType == null || bizId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "bizType/bizId 必填");
        }
        Map<String, Object> pack = switch (bizType) {
            case "tender" -> buildTenderPack(bizId);
            case "contract" -> buildContractPack(bizId);
            case "disposal" -> buildGenericPack(bizType, bizId, "资产处置备案");
            case "rent_relief_major" -> buildGenericPack(bizType, bizId, "大额减免备案");
            default -> buildGenericPack(bizType, bizId, "业务备案");
        };

        ComplianceFiling filing = filingMapper.selectOne(
                new LambdaQueryWrapper<ComplianceFiling>()
                        .eq(ComplianceFiling::getBizType, bizType)
                        .eq(ComplianceFiling::getBizId, bizId)
                        .eq(ComplianceFiling::getStatus, "draft")
                        .last("LIMIT 1"));
        if (filing == null) {
            filing = new ComplianceFiling();
            filing.setBizType(bizType);
            filing.setBizId(bizId);
            filing.setCreatedAt(LocalDateTime.now());
            filing.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        }
        filing.setTitle(String.valueOf(pack.getOrDefault("title", bizType + "-" + bizId)));
        filing.setNeedMeeting(needMeeting);
        filing.setStatus(needMeeting && filing.getMeetingMinutesFileId() == null ? "draft" : "ready");
        try {
            filing.setPackageJson(objectMapper.writeValueAsString(pack));
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "备案包序列化失败");
        }
        filing.setUpdatedAt(LocalDateTime.now());
        if (filing.getId() == null) {
            filingMapper.insert(filing);
        } else {
            filingMapper.updateById(filing);
        }
        return filing;
    }

    @Transactional
    public ComplianceFiling attachMeetingMinutes(Long filingId, Long fileId) {
        ComplianceFiling filing = get(filingId);
        filing.setMeetingMinutesFileId(fileId);
        if (Boolean.TRUE.equals(filing.getNeedMeeting()) && fileId != null) {
            filing.setStatus("ready");
        }
        filing.setUpdatedAt(LocalDateTime.now());
        filingMapper.updateById(filing);
        return filing;
    }

    @Transactional
    public ComplianceFiling archive(Long filingId) {
        ComplianceFiling filing = get(filingId);
        if (!"ready".equals(filing.getStatus()) && filing.getMeetingMinutesFileId() == null
                && Boolean.TRUE.equals(filing.getNeedMeeting())) {
            throw new AppException(ErrorCode.CONFLICT, "三重一大需先上传会议纪要");
        }
        filing.setStatus("archived");
        filing.setUpdatedAt(LocalDateTime.now());
        filingMapper.updateById(filing);
        return filing;
    }

    private Map<String, Object> buildTenderPack(Long announcementId) {
        Map<String, Object> tender = leaseListingService.getFilingPackage(announcementId);
        Map<String, Object> pack = new LinkedHashMap<>(tender);
        TenderAnnouncement ann = announcementMapper.selectById(announcementId);
        pack.put("bizType", "tender");
        pack.put("title", ann == null ? "招租备案" : ann.getTitle());
        pack.put("generatedAt", LocalDateTime.now().toString());
        return pack;
    }

    private Map<String, Object> buildContractPack(Long contractId) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "合同不存在");
        }
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("bizType", "contract");
        pack.put("bizId", contractId);
        pack.put("title", "合同备案-" + contract.getContractNo());
        pack.put("contractNo", contract.getContractNo());
        pack.put("assetId", contract.getAssetId());
        pack.put("tenantId", contract.getTenantId());
        pack.put("status", contract.getStatus());
        pack.put("esignStatus", contract.getEsignStatus());
        pack.put("leaseArea", contract.getLeaseArea());
        pack.put("rentAmount", contract.getRentAmount());
        pack.put("startDate", contract.getStartDate());
        pack.put("endDate", contract.getEndDate());
        pack.put("generatedAt", LocalDateTime.now().toString());
        return pack;
    }

    private Map<String, Object> buildGenericPack(String bizType, Long bizId, String title) {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("bizType", bizType);
        pack.put("bizId", bizId);
        pack.put("title", title + "-" + bizId);
        pack.put("needTripleOne", true);
        pack.put("generatedAt", LocalDateTime.now().toString());
        return pack;
    }
}
