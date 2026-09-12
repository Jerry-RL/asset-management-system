package com.ams.modules.disposal.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.disposal.dto.DisposalCreateRequest;
import com.ams.modules.disposal.entity.DisposalOrder;
import com.ams.modules.disposal.service.DisposalService;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RequiresPerm;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产处置接口（FR-DISP-*）。
 */
@RestController
@RequestMapping("/api/v1/disposals")
public class DisposalController {

    private final DisposalService disposalService;
    private final RecordSheetService recordSheetService;

    public DisposalController(DisposalService disposalService, RecordSheetService recordSheetService) {
        this.disposalService = disposalService;
        this.recordSheetService = recordSheetService;
    }

    @GetMapping
    public ApiResponse<PageResult<DisposalOrder>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(disposalService.page(page, pageSize, status), TraceIdUtil.get());
    }

    @PostMapping
    @RequiresPerm("operation.disposal:create")
    @Audited(module = "disposal", action = "create")
    public ApiResponse<DisposalOrder> create(@RequestBody DisposalCreateRequest request) {
        DisposalOrder order = disposalService.create(request.toOrder());
        // 附件随处置单一起建立关联；disposal_order 的宿主权限跟资产走（设计 §4.3）
        recordSheetService.syncOrderAttachments(order.getId(), request.getAttachments());
        return ApiResponse.ok(order, TraceIdUtil.get());
    }

    @PostMapping("/{disposalId}/submit")
    @RequiresPerm("operation.disposal:create")
    @Audited(module = "disposal", action = "submit")
    public ApiResponse<DisposalOrder> submit(@PathVariable Long disposalId) {
        return ApiResponse.ok(disposalService.submit(disposalId), TraceIdUtil.get());
    }

    @PostMapping("/{disposalId}/approve")
    @RequiresPerm("operation.disposal:approve")
    @Audited(module = "disposal", action = "approve")
    public ApiResponse<Void> approve(@PathVariable Long disposalId) {
        disposalService.onApproved(disposalId);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/{disposalId}/execute")
    @RequiresPerm("operation.disposal:update")
    @Audited(module = "disposal", action = "execute")
    public ApiResponse<DisposalOrder> execute(@PathVariable Long disposalId, @RequestBody Map<String, Object> body) {
        BigDecimal amount = body.get("actualAmount") == null ? null : new BigDecimal(body.get("actualAmount").toString());
        return ApiResponse.ok(disposalService.execute(disposalId, amount, (String) body.get("counterparty")), TraceIdUtil.get());
    }

    @PostMapping("/{disposalId}/complete")
    @RequiresPerm("operation.disposal:update")
    @Audited(module = "disposal", action = "complete")
    public ApiResponse<DisposalOrder> complete(@PathVariable Long disposalId) {
        return ApiResponse.ok(disposalService.complete(disposalId), TraceIdUtil.get());
    }
}
