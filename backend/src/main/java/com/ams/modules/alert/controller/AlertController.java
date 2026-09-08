package com.ams.modules.alert.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.alert.entity.AlertRecord;
import com.ams.modules.alert.entity.AlertRule;
import com.ams.modules.alert.service.AlertService;
import com.ams.platform.security.Audited;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预警管理接口（FR-ALERT-*）。
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> rules(@RequestParam(required = false) String alertType) {
        return ApiResponse.ok(alertService.listRules(alertType), TraceIdUtil.get());
    }

    @PostMapping("/rules")
    @Audited(module = "alert", action = "create_rule")
    public ApiResponse<AlertRule> createRule(@RequestBody AlertRule rule) {
        return ApiResponse.ok(alertService.createRule(rule), TraceIdUtil.get());
    }

    @PutMapping("/rules/{id}")
    @Audited(module = "alert", action = "update_rule")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @RequestBody AlertRule rule) {
        return ApiResponse.ok(alertService.updateRule(id, rule), TraceIdUtil.get());
    }

    @GetMapping("/records")
    public ApiResponse<List<AlertRecord>> records(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(alertService.listRecords(status, assigneeId), TraceIdUtil.get());
    }

    @PostMapping("/records")
    @Audited(module = "alert", action = "trigger")
    public ApiResponse<AlertRecord> trigger(@RequestBody AlertRecord record) {
        return ApiResponse.ok(alertService.trigger(record), TraceIdUtil.get());
    }

    @PostMapping("/records/{id}/assign")
    @Audited(module = "alert", action = "assign")
    public ApiResponse<AlertRecord> assign(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(alertService.assign(id, Long.valueOf(body.get("assigneeId").toString())), TraceIdUtil.get());
    }

    @PostMapping("/records/{id}/process")
    @Audited(module = "alert", action = "process")
    public ApiResponse<AlertRecord> process(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(alertService.process(id, body.get("remark")), TraceIdUtil.get());
    }

    @PostMapping("/records/{id}/close")
    @Audited(module = "alert", action = "close")
    public ApiResponse<AlertRecord> close(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(alertService.close(id, body.get("remark")), TraceIdUtil.get());
    }

    @PostMapping("/records/{id}/escalate")
    @Audited(module = "alert", action = "escalate")
    public ApiResponse<AlertRecord> escalate(@PathVariable Long id) {
        return ApiResponse.ok(alertService.escalate(id), TraceIdUtil.get());
    }
}
