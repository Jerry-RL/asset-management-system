package com.ams.modules.regulation.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.adjustment.entity.FeeReliefRequest;
import com.ams.modules.adjustment.mapper.FeeReliefRequestMapper;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.asset.mapper.AssetMapper;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
import com.ams.modules.contract.ContractStatus;
import com.ams.modules.contract.entity.Contract;
import com.ams.modules.contract.mapper.ContractMapper;
import com.ams.modules.dashboard.service.DashboardService;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.mapper.DisposalOrderMapper;
import com.ams.modules.lease.entity.TenderAnnouncement;
import com.ams.modules.lease.mapper.TenderAnnouncementMapper;
import com.ams.modules.regulation.entity.RegulationReport;
import com.ams.modules.regulation.mapper.RegulationReportMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 监管报送（FR-REG-001）：自动汇总字段口径 + 溯源 + 复核报送。
 */
@Service
public class RegulationReportService {

    private final RegulationReportMapper regulationReportMapper;
    private final DashboardService dashboardService;
    private final AssetMapper assetMapper;
    private final ContractMapper contractMapper;
    private final BillMapper billMapper;
    private final DisposalOrderMapper disposalOrderMapper;
    private final TenderAnnouncementMapper announcementMapper;
    private final FeeReliefRequestMapper feeReliefRequestMapper;
    private final ObjectMapper objectMapper;

    public RegulationReportService(
            RegulationReportMapper regulationReportMapper,
            DashboardService dashboardService,
            AssetMapper assetMapper,
            ContractMapper contractMapper,
            BillMapper billMapper,
            DisposalOrderMapper disposalOrderMapper,
            TenderAnnouncementMapper announcementMapper,
            FeeReliefRequestMapper feeReliefRequestMapper,
            ObjectMapper objectMapper) {
        this.regulationReportMapper = regulationReportMapper;
        this.dashboardService = dashboardService;
        this.assetMapper = assetMapper;
        this.contractMapper = contractMapper;
        this.billMapper = billMapper;
        this.disposalOrderMapper = disposalOrderMapper;
        this.announcementMapper = announcementMapper;
        this.feeReliefRequestMapper = feeReliefRequestMapper;
        this.objectMapper = objectMapper;
    }

    public List<RegulationReport> list(String reportType) {
        return regulationReportMapper.selectList(
                new LambdaQueryWrapper<RegulationReport>()
                        .eq(reportType != null, RegulationReport::getReportType, reportType)
                        .orderByDesc(RegulationReport::getId));
    }

    public RegulationReport get(Long id) {
        return require(id);
    }

    /** 手工提交内容建草稿。 */
    public RegulationReport build(String reportType, String period, String contentJson) {
        RegulationReport report = new RegulationReport();
        report.setReportType(reportType == null ? "quarterly" : reportType);
        report.setPeriod(period);
        report.setContentJson(contentJson);
        report.setStatus("draft");
        report.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        report.setCreatedAt(LocalDateTime.now());
        regulationReportMapper.insert(report);
        return report;
    }

    /** 自动从业务系统汇总报送包。 */
    @Transactional
    public RegulationReport generate(String reportType, String period, Long companyId) {
        String p = period == null || period.isBlank() ? YearMonth.now().toString() : period;
        Map<String, Object> ops = dashboardService.operations(companyId);
        Map<String, Object> content = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();

        content.put("period", p);
        content.put("reportType", reportType == null ? "quarterly" : reportType);
        content.put("generatedAt", LocalDateTime.now().toString());
        content.put("口径说明", Map.of(
                "时点", "统计时点为报送期末日",
                "金额", "含税口径，单位元",
                "内部往来", "默认不抵销"));

        Map<String, Object> assetScale = new LinkedHashMap<>();
        assetScale.put("assetTotal", ops.get("assetTotal"));
        assetScale.put("totalArea", ops.get("totalArea"));
        assetScale.put("vacantArea", ops.get("vacantArea"));
        assetScale.put("leasedRate", ops.get("leasedRate"));
        content.put("资产规模与权属", assetScale);
        sources.add(source("dashboard.operations", "GET /api/v1/dashboard/operations", assetScale));

        long activeContracts = contractMapper.selectCount(
                new LambdaQueryWrapper<Contract>().eq(Contract::getStatus, ContractStatus.ACTIVE));
        long tenderOpen = announcementMapper.selectCount(
                new LambdaQueryWrapper<TenderAnnouncement>().eq(TenderAnnouncement::getStatus, "open"));
        Map<String, Object> lease = Map.of("activeContracts", activeContracts, "openTenders", tenderOpen);
        content.put("合同与招租", lease);
        sources.add(source("contract/tender", "DB contract+tender_announcement", lease));

        Map<String, Object> collect = new LinkedHashMap<>();
        collect.put("receivable", ops.get("receivable"));
        collect.put("received", ops.get("received"));
        collect.put("arrears", ops.get("arrears"));
        collect.put("collectionRate", ops.get("collectionRate"));
        content.put("收缴与欠费", collect);
        sources.add(source("dashboard.operations", "GET /api/v1/dashboard/operations", collect));

        long disposals = disposalOrderMapper.selectCount(
                new LambdaQueryWrapper<DisposalOrder>().ne(DisposalOrder::getStatus, "draft"));
        long transfers = assetMapper.selectCount(new LambdaQueryWrapper<Asset>().isNotNull(Asset::getId));
        content.put("处置与划转", Map.of("disposalOrders", disposals, "assetCountSnapshot", transfers));
        sources.add(source("disposal", "DB disposal_order", Map.of("disposalOrders", disposals)));

        long lowPrice = contractMapper.selectCount(
                new LambdaQueryWrapper<Contract>().eq(Contract::getSpecialApprovalRequired, true));
        long majorRelief = feeReliefRequestMapper.selectCount(
                new LambdaQueryWrapper<FeeReliefRequest>().eq(FeeReliefRequest::getMajorFlag, true));
        List<Bill> reduced = billMapper.selectList(
                new LambdaQueryWrapper<Bill>().eq(Bill::getStatus, BillStatus.REDUCED).last("LIMIT 50"));
        BigDecimal reliefAmt = reduced.stream()
                .map(b -> b.getReducedAmount() == null ? BigDecimal.ZERO : b.getReducedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> major = Map.of(
                "lowPriceContractCount", lowPrice,
                "majorReliefCount", majorRelief,
                "reducedBillAmount", reliefAmt);
        content.put("低价与减免重大事项", major);
        sources.add(source("adj/price", "DB contract+fee_relief+bill", major));

        RegulationReport report = new RegulationReport();
        report.setReportType(reportType == null ? "quarterly" : reportType);
        report.setPeriod(p);
        report.setStatus("draft");
        report.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        report.setCreatedAt(LocalDateTime.now());
        try {
            report.setContentJson(objectMapper.writeValueAsString(content));
            report.setSourceJson(objectMapper.writeValueAsString(sources));
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "报送包序列化失败");
        }
        regulationReportMapper.insert(report);
        return report;
    }

    public RegulationReport review(Long id, String contentJson) {
        RegulationReport report = require(id);
        if (contentJson != null) {
            report.setContentJson(contentJson);
        }
        report.setStatus("reviewed");
        regulationReportMapper.updateById(report);
        return report;
    }

    public RegulationReport submit(Long id) {
        RegulationReport report = require(id);
        if (!"reviewed".equals(report.getStatus()) && !"draft".equals(report.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "当前状态不可报送");
        }
        if (!"reviewed".equals(report.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "须人工复核后再报送");
        }
        report.setStatus("submitted");
        report.setSubmittedAt(LocalDateTime.now());
        regulationReportMapper.updateById(report);
        return report;
    }

    private Map<String, Object> source(String key, String ref, Object sample) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("ref", ref);
        m.put("sample", sample);
        return m;
    }

    private RegulationReport require(Long id) {
        RegulationReport report = regulationReportMapper.selectById(id);
        if (report == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return report;
    }
}
