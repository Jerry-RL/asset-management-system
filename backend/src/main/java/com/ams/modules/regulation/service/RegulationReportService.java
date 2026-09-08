package com.ams.modules.regulation.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.regulation.entity.RegulationReport;
import com.ams.modules.regulation.mapper.RegulationReportMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 监管报送（FR-REG-001）：报送字段、统计口径、溯源、人工复核后报送。
 */
@Service
public class RegulationReportService {

    private final RegulationReportMapper regulationReportMapper;

    public RegulationReportService(RegulationReportMapper regulationReportMapper) {
        this.regulationReportMapper = regulationReportMapper;
    }

    public List<RegulationReport> list(String reportType) {
        return regulationReportMapper.selectList(
                new LambdaQueryWrapper<RegulationReport>()
                        .eq(reportType != null, RegulationReport::getReportType, reportType)
                        .orderByDesc(RegulationReport::getId));
    }

    public RegulationReport build(String reportType, String period, String contentJson) {
        RegulationReport report = new RegulationReport();
        report.setReportType(reportType);
        report.setPeriod(period);
        report.setContentJson(contentJson);
        report.setStatus("draft");
        report.setCreatedBy(SecurityUtils.currentUserIdOrNull());
        report.setCreatedAt(LocalDateTime.now());
        regulationReportMapper.insert(report);
        return report;
    }

    /** 人工复核（FR-REG-001 溯源复核后报送）。 */
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
        report.setStatus("submitted");
        report.setSubmittedAt(LocalDateTime.now());
        regulationReportMapper.updateById(report);
        return report;
    }

    private RegulationReport require(Long id) {
        RegulationReport report = regulationReportMapper.selectById(id);
        if (report == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return report;
    }
}
