package com.ams.modules.intelligence.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.dashboard.service.DashboardService;
import com.ams.modules.intelligence.entity.AgentPromptTemplate;
import com.ams.modules.intelligence.entity.AgentReport;
import com.ams.modules.intelligence.entity.AgentReportCitation;
import com.ams.modules.intelligence.entity.AgentRun;
import com.ams.modules.intelligence.entity.AgentSession;
import com.ams.modules.intelligence.entity.AgentToolCall;
import com.ams.modules.intelligence.mapper.AgentPromptTemplateMapper;
import com.ams.modules.intelligence.mapper.AgentReportCitationMapper;
import com.ams.modules.intelligence.mapper.AgentReportMapper;
import com.ams.modules.intelligence.mapper.AgentRunMapper;
import com.ams.modules.intelligence.mapper.AgentSessionMapper;
import com.ams.modules.intelligence.mapper.AgentToolCallMapper;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 智能 Agent（FR-AI-*，NFR-AI-*）：
 *  - 报告模板库、模板一键生成（只读 Tool）
 *  - 溯源（citation）、导出校验（verified 门禁）
 *  - LLM 不可用时降级为「模板 + 固定 Tool 只读报告」（NFR-AI-008）
 * 生产对接私有化 LLM（LlmGateway）；本实现覆盖编排、审计、溯源与只读边界。
 */
@Service
public class IntelligenceService {

    private final AgentSessionMapper sessionMapper;
    private final AgentRunMapper runMapper;
    private final AgentToolCallMapper toolCallMapper;
    private final AgentReportMapper reportMapper;
    private final AgentReportCitationMapper citationMapper;
    private final AgentPromptTemplateMapper templateMapper;
    private final DashboardService dashboardService;

    public IntelligenceService(
            AgentSessionMapper sessionMapper,
            AgentRunMapper runMapper,
            AgentToolCallMapper toolCallMapper,
            AgentReportMapper reportMapper,
            AgentReportCitationMapper citationMapper,
            AgentPromptTemplateMapper templateMapper,
            DashboardService dashboardService) {
        this.sessionMapper = sessionMapper;
        this.runMapper = runMapper;
        this.toolCallMapper = toolCallMapper;
        this.reportMapper = reportMapper;
        this.citationMapper = citationMapper;
        this.templateMapper = templateMapper;
        this.dashboardService = dashboardService;
    }

    // ---- 会话 ----
    public AgentSession createSession(String title, Long companyId) {
        AgentSession session = new AgentSession();
        session.setUserId(SecurityUtils.currentUserIdOrNull());
        session.setCompanyId(companyId);
        session.setTitle(title == null ? "新会话" : title);
        session.setStatus("active");
        session.setCreatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    public List<AgentSession> listSessions() {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<AgentSession>()
                        .eq(AgentSession::getUserId, SecurityUtils.currentUserIdOrNull())
                        .orderByDesc(AgentSession::getId));
    }

    // ---- 模板 ----
    public List<AgentPromptTemplate> listTemplates() {
        return templateMapper.selectList(
                new LambdaQueryWrapper<AgentPromptTemplate>()
                        .eq(AgentPromptTemplate::getEnabled, true));
    }

    /**
     * 按模板生成报告（FR-AI-008）：拉取系统数据（只读 Tool）→ 生成报告 + 溯源。
     * LLM 不可用降级：模板 + 固定 Tool 只读报告，verified=true。
     */
    @Transactional
    public AgentReport generateReport(Long sessionId, String templateCode, Long companyId) {
        AgentPromptTemplate template = null;
        if (templateCode != null) {
            template = templateMapper.selectOne(
                    new LambdaQueryWrapper<AgentPromptTemplate>()
                            .eq(AgentPromptTemplate::getTemplateCode, templateCode));
            if (template == null) {
                throw new AppException(ErrorCode.NOT_FOUND, "报告模板不存在");
            }
        }
        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setUserId(SecurityUtils.currentUserIdOrNull());
        run.setIntent("report_generate");
        run.setTemplateId(templateCode);
        run.setStatus("running");
        run.setTraceId(TraceIdUtil.get() == null ? UUID.randomUUID().toString().replace("-", "") : TraceIdUtil.get());
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        // 只读 Tool 调用：经营指标
        long start = System.currentTimeMillis();
        Map<String, Object> metrics = dashboardService.operations(companyId);
        AgentToolCall toolCall = new AgentToolCall();
        toolCall.setRunId(run.getId());
        toolCall.setToolName("dashboard.operations");
        toolCall.setRequestJson("{\"companyId\":" + companyId + "}");
        toolCall.setResponseSummary(String.valueOf(metrics));
        toolCall.setDurationMs((int) (System.currentTimeMillis() - start));
        toolCall.setCreatedAt(LocalDateTime.now());
        toolCallMapper.insert(toolCall);

        AgentReport report = new AgentReport();
        report.setRunId(run.getId());
        report.setTitle(template == null ? "自定义报告" : template.getName());
        report.setFormat("html");
        report.setStatus("draft");
        report.setVerified(true); // 仅 Tool 数据，可验证
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);

        // 溯源：核心指标绑定 sourceRef
        addCitation(report.getId(), "assetTotal", String.valueOf(metrics.get("assetTotal")),
                toolCall.getId(), "GET /api/v1/dashboard/operations");
        addCitation(report.getId(), "leasedRate", String.valueOf(metrics.get("leasedRate")),
                toolCall.getId(), "GET /api/v1/dashboard/operations");
        addCitation(report.getId(), "collectionRate", String.valueOf(metrics.get("collectionRate")),
                toolCall.getId(), "GET /api/v1/dashboard/operations");
        addCitation(report.getId(), "vacantArea", String.valueOf(metrics.get("vacantArea")),
                toolCall.getId(), "GET /api/v1/dashboard/operations");

        run.setStatus("succeeded");
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
        return report;
    }

    /** 报告详情 + citations。 */
    public AgentReport getReport(Long reportId) {
        AgentReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return report;
    }

    public List<AgentReportCitation> citations(Long reportId) {
        return citationMapper.selectList(
                new LambdaQueryWrapper<AgentReportCitation>()
                        .eq(AgentReportCitation::getReportId, reportId));
    }

    /** 导出门禁（FR-AI-019）：未通过溯源校验阻断导出。 */
    public void assertVerified(Long reportId) {
        AgentReport report = getReport(reportId);
        if (!Boolean.TRUE.equals(report.getVerified())) {
            throw new AppException(ErrorCode.REPORT_NOT_VERIFIED);
        }
    }

    /** 对外发布审批（FR-AI-021）。 */
    public AgentReport approve(Long reportId) {
        AgentReport report = getReport(reportId);
        report.setStatus("approved");
        report.setApprovedBy(SecurityUtils.currentUserIdOrNull());
        reportMapper.updateById(report);
        return report;
    }

    /** 溯源链路（FR-AI-024 管理员审计）。 */
    public List<AgentToolCall> toolCalls(Long runId) {
        return toolCallMapper.selectList(
                new LambdaQueryWrapper<AgentToolCall>().eq(AgentToolCall::getRunId, runId));
    }

    private void addCitation(Long reportId, String claimKey, String claimValue, Long toolCallId, String apiPath) {
        AgentReportCitation citation = new AgentReportCitation();
        citation.setReportId(reportId);
        citation.setClaimKey(claimKey);
        citation.setClaimValue(claimValue);
        citation.setToolCallId(toolCallId);
        citation.setApiPath(apiPath);
        citation.setVerified(true);
        citationMapper.insert(citation);
    }
}
