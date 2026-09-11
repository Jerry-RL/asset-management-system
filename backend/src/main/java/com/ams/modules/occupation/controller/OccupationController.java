package com.ams.modules.occupation.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.occupation.entity.OccupationOrder;
import com.ams.modules.occupation.service.OccupationService;
import com.ams.platform.security.Audited;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 临时占用接口（FR-OCC-*）。
 */
@RestController
@RequestMapping("/api/v1/occupations")
public class OccupationController {

    private final OccupationService occupationService;

    public OccupationController(OccupationService occupationService) {
        this.occupationService = occupationService;
    }

    @GetMapping
    public ApiResponse<List<OccupationOrder>> list() {
        return ApiResponse.ok(occupationService.list(), TraceIdUtil.get());
    }

    @PostMapping
    @Audited(module = "occupation", action = "create")
    public ApiResponse<OccupationOrder> create(@RequestBody OccupationOrder order) {
        return ApiResponse.ok(occupationService.create(order), TraceIdUtil.get());
    }

    @PostMapping("/{id}/submit")
    @Audited(module = "occupation", action = "submit")
    public ApiResponse<OccupationOrder> submit(@PathVariable Long id) {
        return ApiResponse.ok(occupationService.submit(id), TraceIdUtil.get());
    }

    @PostMapping("/{id}/approve")
    @Audited(module = "occupation", action = "approve")
    public ApiResponse<OccupationOrder> approve(@PathVariable Long id) {
        return ApiResponse.ok(occupationService.approve(id), TraceIdUtil.get());
    }

    @PostMapping("/{occupationId}/release")
    @Audited(module = "occupation", action = "release")
    public ApiResponse<OccupationOrder> release(@PathVariable Long occupationId) {
        return ApiResponse.ok(occupationService.release(occupationId), TraceIdUtil.get());
    }

    /**
     * 撤销/驳回：收口预留。
     *
     * <p>必须可达：否则 {@code submit} 写入的排他预留行会永久锁死单元。
     * 自动驳回见 {@code OccupationService.onApprovalCompleted}。
     */
    @PostMapping("/{id}/withdraw")
    @Audited(module = "occupation", action = "withdraw")
    public ApiResponse<OccupationOrder> withdraw(@PathVariable Long id,
            @RequestParam(required = false) String remark) {
        return ApiResponse.ok(occupationService.withdraw(id, remark), TraceIdUtil.get());
    }
}
