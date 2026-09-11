package com.ams.modules.selfuse.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.selfuse.entity.SelfUseOrder;
import com.ams.modules.selfuse.service.SelfUseService;
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
 * 资产自用接口（FR-SELF-*）。
 */
@RestController
@RequestMapping("/api/v1/self-uses")
public class SelfUseController {

    private final SelfUseService selfUseService;

    public SelfUseController(SelfUseService selfUseService) {
        this.selfUseService = selfUseService;
    }

    @GetMapping
    public ApiResponse<List<SelfUseOrder>> list() {
        return ApiResponse.ok(selfUseService.list(), TraceIdUtil.get());
    }

    @PostMapping
    @Audited(module = "self_use", action = "create")
    public ApiResponse<SelfUseOrder> create(@RequestBody SelfUseOrder order) {
        return ApiResponse.ok(selfUseService.create(order), TraceIdUtil.get());
    }

    @PostMapping("/{id}/submit")
    @Audited(module = "self_use", action = "submit")
    public ApiResponse<SelfUseOrder> submit(@PathVariable Long id) {
        return ApiResponse.ok(selfUseService.submit(id), TraceIdUtil.get());
    }

    @PostMapping("/{id}/approve")
    @Audited(module = "self_use", action = "approve")
    public ApiResponse<SelfUseOrder> approve(@PathVariable Long id) {
        return ApiResponse.ok(selfUseService.approve(id), TraceIdUtil.get());
    }

    /**
     * 撤销/驳回：收口预留。
     *
     * <p>必须可达：否则 {@code submit} 写入的排他预留行会永久锁死单元。
     * 自动驳回见 {@code SelfUseService.onApprovalCompleted}。
     */
    @PostMapping("/{id}/withdraw")
    @Audited(module = "self_use", action = "withdraw")
    public ApiResponse<SelfUseOrder> withdraw(@PathVariable Long id,
            @RequestParam(required = false) String remark) {
        return ApiResponse.ok(selfUseService.withdraw(id, remark), TraceIdUtil.get());
    }

    @PostMapping("/{id}/end")
    @Audited(module = "self_use", action = "end")
    public ApiResponse<SelfUseOrder> end(@PathVariable Long id) {
        return ApiResponse.ok(selfUseService.end(id), TraceIdUtil.get());
    }
}
