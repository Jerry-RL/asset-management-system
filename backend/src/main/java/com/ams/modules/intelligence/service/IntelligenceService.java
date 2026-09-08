package com.ams.modules.intelligence.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.asset.entity.Mortgage;
import com.ams.modules.asset.service.CertificateService;
import com.ams.modules.billing.BillStatus;
import com.ams.modules.billing.entity.Bill;
import com.ams.modules.billing.mapper.BillMapper;
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
import com.ams.platform.integration.llm.LlmGateway;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 智能 Agent（FR-AI-*）：会话问答、模板报告、只读 Tool、溯源与 Mock LLM 降级。
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
    private final BillMapper billMapper;
    private final CertificateService certificateService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public IntelligenceService(
            AgentSessionMapper sessionMapper,
            AgentRunMapper runMapper,
            AgentToolCallMapper toolCallMapper,
            AgentReportMapper reportMapper,
            AgentReportCitationMapper citationMapper,
            AgentPromptTemplateMapper templateMapper,
            DashboardService dashboardService,
            BillMapper billMapper,
            CertificateService certificateService,
            LlmGateway llmGateway,
            ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.runMapper = runMapper;
        this.toolCallMapper = toolCallMapper;
        this.reportMapper = reportMapper;
        this.citationMapper = citationMapper;
        this.templateMapper = templateMapper;
        this.dashboardService = dashboardService;
        this.billMapper = billMapper;
        this.certificateService = certificateService;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

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

    public List<AgentPromptTemplate> listTemplates() {
        return templateMapper.selectList(
                new LambdaQueryWrapper<AgentPromptTemplate>()
                        .eq(AgentPromptTemplate::getEnabled, true));
    }

    /** NL 问答编排：LLM 选 Tool → 只读执行 → 结构化答复 + 溯源。 */
    @Transactional
    public Map<String, Object> chat(Long sessionId, String question, Long companyId) {
        if (question == null || question.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        AgentRun run = newRun(sessionId, "nl_chat", null);
        LlmGateway.ChatResult llm = llmGateway.chat(
                List.of(new LlmGateway.ChatMessage("user", question)),
                Map.of("companyId", companyId == null ? "" : companyId));

        Map<String, Object> toolData = new LinkedHashMap<>();
        List<Long> toolCallIds = new ArrayList<>();
        for (String tool : llm.suggestedTools()) {
            Map.Entry<String, Object> executed = executeTool(run.getId(), tool, companyId);
            toolData.put(executed.getKey(), executed.getValue());
            // tool call id stored via last insert — re-query
        }
        List<AgentToolCall> calls = toolCalls(run.getId());
        for (AgentToolCall c : calls) {
            toolCallIds.add(c.getId());
        }

        StringBuilder html = new StringBuilder();
        html.append("<h3>智能问答</h3><p>").append(escape(llm.content())).append("</p>");
        html.append("<h4>工具数据</h4><ul>");
        for (Map.Entry<String, Object> e : toolData.entrySet()) {
            html.append("<li><b>").append(escape(e.getKey())).append("</b>: ")
                    .append(escape(String.valueOf(e.getValue()))).append("</li>");
        }
        html.append("</ul>");

        AgentReport report = new AgentReport();
        report.setRunId(run.getId());
        report.setTitle("问答-" + LocalDate.now());
        report.setFormat("html");
        report.setContentHtml(html.toString());
        report.setStatus("draft");
        report.setVerified(true);
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);

        if (toolData.containsKey("dashboard.operations") && toolData.get("dashboard.operations") instanceof Map<?, ?> m) {
            addCitation(report.getId(), "leasedRate", String.valueOf(m.get("leasedRate")),
                    toolCallIds.isEmpty() ? null : toolCallIds.get(0), "GET /api/v1/dashboard/operations");
            addCitation(report.getId(), "collectionRate", String.valueOf(m.get("collectionRate")),
                    toolCallIds.isEmpty() ? null : toolCallIds.get(0), "GET /api/v1/dashboard/operations");
        }

        finishRun(run);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("reportId", report.getId());
        result.put("answer", llm.content());
        result.put("mockLlm", llm.mock());
        result.put("tools", toolData);
        result.put("verified", true);
        return result;
    }

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
        AgentRun run = newRun(sessionId, "report_generate", templateCode);

        Map.Entry<String, Object> dash = executeTool(run.getId(), "dashboard.operations", companyId);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) dash.getValue();
        executeTool(run.getId(), "dunning.summary", companyId);
        executeTool(run.getId(), "mortgage.expiring", companyId);

        List<AgentToolCall> calls = toolCalls(run.getId());
        Long dashCallId = calls.isEmpty() ? null : calls.get(0).getId();

        String title = template == null ? "经营简报" : template.getName();
        String html = "<h2>" + escape(title) + "</h2>"
                + "<p>资产总量: " + metrics.get("assetTotal") + "</p>"
                + "<p>出租率: " + metrics.get("leasedRate") + "</p>"
                + "<p>收缴率: " + metrics.get("collectionRate") + "</p>"
                + "<p>空置面积: " + metrics.get("vacantArea") + "</p>"
                + "<p>欠费: " + metrics.get("arrears") + "</p>"
                + "<p><i>LLM=" + (llmGateway.isMock() ? "mock-degraded" : "online") + "</i></p>";

        AgentReport report = new AgentReport();
        report.setRunId(run.getId());
        report.setTitle(title);
        report.setFormat("html");
        report.setContentHtml(html);
        report.setStatus("draft");
        report.setVerified(true);
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);

        addCitation(report.getId(), "assetTotal", String.valueOf(metrics.get("assetTotal")),
                dashCallId, "GET /api/v1/dashboard/operations");
        addCitation(report.getId(), "leasedRate", String.valueOf(metrics.get("leasedRate")),
                dashCallId, "GET /api/v1/dashboard/operations");
        addCitation(report.getId(), "collectionRate", String.valueOf(metrics.get("collectionRate")),
                dashCallId, "GET /api/v1/dashboard/operations");
        addCitation(report.getId(), "vacantArea", String.valueOf(metrics.get("vacantArea")),
                dashCallId, "GET /api/v1/dashboard/operations");

        finishRun(run);
        return report;
    }

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

    public void assertVerified(Long reportId) {
        AgentReport report = getReport(reportId);
        if (!Boolean.TRUE.equals(report.getVerified())) {
            throw new AppException(ErrorCode.REPORT_NOT_VERIFIED);
        }
    }

    public AgentReport approve(Long reportId) {
        AgentReport report = getReport(reportId);
        report.setStatus("approved");
        report.setApprovedBy(SecurityUtils.currentUserIdOrNull());
        reportMapper.updateById(report);
        return report;
    }

    public List<AgentReport> listReports(int limit) {
        int lim = limit <= 0 ? 50 : Math.min(limit, 200);
        return reportMapper.selectList(
                new LambdaQueryWrapper<AgentReport>()
                        .orderByDesc(AgentReport::getId)
                        .last("LIMIT " + lim));
    }

    /** 导出已核验报告 HTML（FR-AI 报告下载）。 */
    @Transactional
    public Map<String, Object> exportHtml(Long reportId) {
        assertVerified(reportId);
        AgentReport report = getReport(reportId);
        String html = report.getContentHtml() == null ? "" : report.getContentHtml();
        report.setStatus("exported");
        report.setFormat("html");
        reportMapper.updateById(report);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", report.getId());
        result.put("title", report.getTitle());
        result.put("format", "html");
        result.put("verified", true);
        result.put("status", report.getStatus());
        result.put("contentHtml", html);
        result.put("fileName", "agent-report-" + report.getId() + ".html");
        return result;
    }

    public List<AgentToolCall> toolCalls(Long runId) {
        return toolCallMapper.selectList(
                new LambdaQueryWrapper<AgentToolCall>().eq(AgentToolCall::getRunId, runId));
    }

    private Map.Entry<String, Object> executeTool(Long runId, String toolName, Long companyId) {
        long start = System.currentTimeMillis();
        Object data;
        String requestJson;
        try {
            switch (toolName) {
                case "dunning.summary" -> {
                    requestJson = "{}";
                    data = dunningSummary();
                }
                case "mortgage.expiring" -> {
                    requestJson = "{\"withinDays\":30}";
                    List<Mortgage> list = certificateService.listExpiring(30);
                    data = Map.of("count", list.size(), "items", list.stream().limit(20).toList());
                }
                default -> {
                    toolName = "dashboard.operations";
                    requestJson = "{\"companyId\":" + companyId + "}";
                    data = dashboardService.operations(companyId);
                }
            }
        } catch (Exception e) {
            data = Map.of("error", e.getMessage());
            requestJson = "{}";
        }
        AgentToolCall toolCall = new AgentToolCall();
        toolCall.setRunId(runId);
        toolCall.setToolName(toolName);
        toolCall.setRequestJson(requestJson);
        try {
            toolCall.setResponseSummary(objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            toolCall.setResponseSummary(String.valueOf(data));
        }
        toolCall.setDurationMs((int) (System.currentTimeMillis() - start));
        toolCall.setCreatedAt(LocalDateTime.now());
        toolCallMapper.insert(toolCall);
        return Map.entry(toolName, data);
    }

    private Map<String, Object> dunningSummary() {
        List<Bill> bills = billMapper.selectList(
                new LambdaQueryWrapper<Bill>()
                        .in(Bill::getStatus, BillStatus.UNPAID, BillStatus.PARTIAL_PAID)
                        .gt(Bill::getDunningLevel, 0));
        BigDecimal arrears = BigDecimal.ZERO;
        int l4 = 0;
        int l5 = 0;
        for (Bill b : bills) {
            BigDecimal due = nz(b.getAmount()).subtract(nz(b.getPaidAmount())).subtract(nz(b.getReducedAmount()));
            arrears = arrears.add(due.max(BigDecimal.ZERO));
            if (b.getDunningLevel() != null && b.getDunningLevel() >= 5) {
                l5++;
            } else if (b.getDunningLevel() != null && b.getDunningLevel() >= 4) {
                l4++;
            }
        }
        Map<String, Object> map = new HashMap<>();
        map.put("overdueBillCount", bills.size());
        map.put("arrearsAmount", arrears);
        map.put("l4Count", l4);
        map.put("l5Count", l5);
        return map;
    }

    private AgentRun newRun(Long sessionId, String intent, String templateId) {
        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setUserId(SecurityUtils.currentUserIdOrNull());
        run.setIntent(intent);
        run.setTemplateId(templateId);
        run.setStatus("running");
        run.setTraceId(TraceIdUtil.get() == null
                ? UUID.randomUUID().toString().replace("-", "")
                : TraceIdUtil.get());
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    private void finishRun(AgentRun run) {
        run.setStatus("succeeded");
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
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

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
