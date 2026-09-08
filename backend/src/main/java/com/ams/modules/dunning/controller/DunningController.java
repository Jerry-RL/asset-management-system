package com.ams.modules.dunning.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.dunning.entity.DunningRecord;
import com.ams.modules.dunning.service.DunningService;
import com.ams.platform.security.Audited;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 履约催缴接口（FR-DUN-*）。
 */
@RestController
@RequestMapping("/api/v1/dunning")
public class DunningController {

    private final DunningService dunningService;

    public DunningController(DunningService dunningService) {
        this.dunningService = dunningService;
    }

    @PostMapping("/scan")
    @Audited(module = "dunning", action = "scan_overdue")
    public ApiResponse<Integer> scan() {
        return ApiResponse.ok(dunningService.scanOverdue(), TraceIdUtil.get());
    }

    @PostMapping("/records")
    @Audited(module = "dunning", action = "create_record")
    public ApiResponse<DunningRecord> record(@RequestBody Map<String, Object> body) {
        Long billId = Long.valueOf(body.get("billId").toString());
        Integer level = body.get("level") == null ? null : Integer.parseInt(body.get("level").toString());
        return ApiResponse.ok(dunningService.record(billId, level,
                (String) body.get("method"), (String) body.get("content"),
                (String) body.get("tenantFeedback"), (String) body.get("result")), TraceIdUtil.get());
    }

    @GetMapping("/records")
    public ApiResponse<List<DunningRecord>> records(@RequestParam(required = false) Long contractId) {
        return ApiResponse.ok(dunningService.listByContract(contractId), TraceIdUtil.get());
    }
}
