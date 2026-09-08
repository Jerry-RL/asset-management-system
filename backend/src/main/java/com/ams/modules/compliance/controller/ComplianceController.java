package com.ams.modules.compliance.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.compliance.entity.ComplianceFiling;
import com.ams.modules.compliance.service.ComplianceService;
import com.ams.platform.security.Audited;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合规备案（FR-COMP）。
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    private final ComplianceService complianceService;

    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @GetMapping("/filings")
    public ApiResponse<List<ComplianceFiling>> list(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long bizId) {
        return ApiResponse.ok(complianceService.list(bizType, bizId), TraceIdUtil.get());
    }

    @GetMapping("/filings/{id}")
    public ApiResponse<ComplianceFiling> get(@PathVariable Long id) {
        return ApiResponse.ok(complianceService.get(id), TraceIdUtil.get());
    }

    @PostMapping("/filings/build")
    @Audited(module = "compliance", action = "build")
    public ApiResponse<ComplianceFiling> build(@RequestBody Map<String, Object> body) {
        String bizType = (String) body.get("bizType");
        Long bizId = Long.valueOf(body.get("bizId").toString());
        boolean needMeeting = body.get("needMeeting") != null
                && Boolean.parseBoolean(body.get("needMeeting").toString());
        return ApiResponse.ok(complianceService.build(bizType, bizId, needMeeting), TraceIdUtil.get());
    }

    @PostMapping("/filings/{id}/meeting-minutes")
    @Audited(module = "compliance", action = "attach_minutes")
    public ApiResponse<ComplianceFiling> meetingMinutes(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(complianceService.attachMeetingMinutes(id,
                Long.valueOf(body.get("fileId").toString())), TraceIdUtil.get());
    }

    @PostMapping("/filings/{id}/archive")
    @Audited(module = "compliance", action = "archive")
    public ApiResponse<ComplianceFiling> archive(@PathVariable Long id) {
        return ApiResponse.ok(complianceService.archive(id), TraceIdUtil.get());
    }
}
