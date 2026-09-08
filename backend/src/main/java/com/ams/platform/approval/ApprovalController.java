package com.ams.platform.approval;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.platform.approval.entity.ApprovalInstance;
import com.ams.platform.approval.entity.ApprovalTask;
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
 * 审批接口（docs/api/openapi.yaml /approvals/*）。
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalEngine approvalEngine;

    public ApprovalController(ApprovalEngine approvalEngine) {
        this.approvalEngine = approvalEngine;
    }

    @GetMapping("/tasks")
    public ApiResponse<List<ApprovalTask>> tasks() {
        return ApiResponse.ok(approvalEngine.myPendingTasks(), TraceIdUtil.get());
    }

    @GetMapping("/inbox")
    public ApiResponse<List<Map<String, Object>>> inbox() {
        return ApiResponse.ok(approvalEngine.inbox(), TraceIdUtil.get());
    }

    @GetMapping("/instances")
    public ApiResponse<List<ApprovalInstance>> instances(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizType) {
        return ApiResponse.ok(approvalEngine.listInstances(status, bizType), TraceIdUtil.get());
    }

    @PostMapping("/{instanceId}/approve")
    public ApiResponse<ApprovalInstance> approve(
            @PathVariable Long instanceId, @RequestBody(required = false) Map<String, String> body) {
        String comment = body == null ? null : body.get("comment");
        return ApiResponse.ok(approvalEngine.approve(instanceId, comment), TraceIdUtil.get());
    }

    @PostMapping("/{instanceId}/reject")
    public ApiResponse<ApprovalInstance> reject(
            @PathVariable Long instanceId, @RequestBody(required = false) Map<String, String> body) {
        String comment = body == null ? null : body.get("comment");
        return ApiResponse.ok(approvalEngine.reject(instanceId, comment), TraceIdUtil.get());
    }
}
