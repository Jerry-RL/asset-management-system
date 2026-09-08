package com.ams.modules.intelligence.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.intelligence.entity.AgentPromptTemplate;
import com.ams.modules.intelligence.entity.AgentReport;
import com.ams.modules.intelligence.entity.AgentReportCitation;
import com.ams.modules.intelligence.entity.AgentSession;
import com.ams.modules.intelligence.entity.AgentToolCall;
import com.ams.modules.intelligence.service.IntelligenceService;
import com.ams.platform.security.Audited;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能中心接口（FR-AI-*）。
 */
@RestController
@RequestMapping("/api/v1/intelligence")
public class IntelligenceController {

    private final IntelligenceService intelligenceService;

    public IntelligenceController(IntelligenceService intelligenceService) {
        this.intelligenceService = intelligenceService;
    }

    @PostMapping("/sessions")
    public ApiResponse<AgentSession> createSession(@RequestBody Map<String, Object> body) {
        Long companyId = body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString());
        return ApiResponse.ok(intelligenceService.createSession((String) body.get("title"), companyId), TraceIdUtil.get());
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AgentSession>> sessions() {
        return ApiResponse.ok(intelligenceService.listSessions(), TraceIdUtil.get());
    }

    @GetMapping("/templates")
    public ApiResponse<List<AgentPromptTemplate>> templates() {
        return ApiResponse.ok(intelligenceService.listTemplates(), TraceIdUtil.get());
    }

    @PostMapping("/chat")
    @Audited(module = "intelligence", action = "chat")
    public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        Long sessionId = body.get("sessionId") == null ? null : Long.valueOf(body.get("sessionId").toString());
        Long companyId = body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString());
        return ApiResponse.ok(intelligenceService.chat(sessionId, (String) body.get("question"), companyId),
                TraceIdUtil.get());
    }

    @PostMapping("/reports/generate")
    @Audited(module = "intelligence", action = "generate_report")
    public ApiResponse<AgentReport> generate(@RequestBody Map<String, Object> body) {
        Long sessionId = body.get("sessionId") == null ? null : Long.valueOf(body.get("sessionId").toString());
        Long companyId = body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString());
        String templateCode = (String) body.get("templateCode");
        return ApiResponse.ok(intelligenceService.generateReport(sessionId, templateCode, companyId), TraceIdUtil.get());
    }

    @GetMapping("/reports")
    public ApiResponse<List<AgentReport>> reports() {
        return ApiResponse.ok(intelligenceService.listReports(100), TraceIdUtil.get());
    }

    @GetMapping("/reports/{reportId}")
    public ApiResponse<AgentReport> report(@PathVariable Long reportId) {
        return ApiResponse.ok(intelligenceService.getReport(reportId), TraceIdUtil.get());
    }

    @GetMapping("/reports/{reportId}/citations")
    public ApiResponse<List<AgentReportCitation>> citations(@PathVariable Long reportId) {
        return ApiResponse.ok(intelligenceService.citations(reportId), TraceIdUtil.get());
    }

    @PostMapping("/reports/{reportId}/approve")
    @Audited(module = "intelligence", action = "approve_report")
    public ApiResponse<AgentReport> approve(@PathVariable Long reportId) {
        return ApiResponse.ok(intelligenceService.approve(reportId), TraceIdUtil.get());
    }

    @PostMapping("/reports/{reportId}/download")
    @Audited(module = "intelligence", action = "download_report")
    public ApiResponse<Map<String, Object>> download(@PathVariable Long reportId) {
        return ApiResponse.ok(intelligenceService.exportHtml(reportId), TraceIdUtil.get());
    }

    @GetMapping("/runs/{runId}/trace")
    public ApiResponse<List<AgentToolCall>> trace(@PathVariable Long runId) {
        return ApiResponse.ok(intelligenceService.toolCalls(runId), TraceIdUtil.get());
    }
}
