package com.ams.modules.ownership.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.ownership.dto.OwnershipTransferInput;
import com.ams.modules.ownership.dto.OwnershipTransferView;
import com.ams.modules.ownership.dto.TransferAssetOption;
import com.ams.modules.ownership.service.OwnershipTransferService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RequiresPerm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权属流转接口（设计 §5.1）。
 *
 * <p>权限码固定 4 个：{@code deed.ownershipTransfer:view|create|update|delete}。
 * 「生效」用 {@code :update} 而不是 {@code :approve}：本期没有审批环节，而仓内
 * {@code :approve} 特指「审批别人的单」（处置 / 占用 / 自用）；仓内推进状态机一律用
 * {@code :update}（{@code operation.disposal} 的 execute / complete 即是）。
 */
@RestController
@RequestMapping("/api/v1/ownership-transfers")
public class OwnershipTransferController {

    private final OwnershipTransferService service;

    public OwnershipTransferController(OwnershipTransferService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPerm("deed.ownershipTransfer:view")
    public ApiResponse<PageResult<OwnershipTransferView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Long fromCompanyId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(
                service.page(page, pageSize, status, direction, fromCompanyId, keyword),
                TraceIdUtil.get());
    }

    /**
     * 资产下拉（按原公司 + 权属类型联动过滤）。
     *
     * <p><b>必须声明在 {@code /{id}} 之前</b>：否则「asset-options」会被 {@code @PathVariable}
     * 抢匹配，然后因无法转成 {@code Long} 直接 500。
     */
    @GetMapping("/asset-options")
    @RequiresPerm("deed.ownershipTransfer:view")
    public ApiResponse<PageResult<TransferAssetOption>> assetOptions(
            @RequestParam Long companyId,
            @RequestParam(required = false) String transferScope,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long pageSize) {
        return ApiResponse.ok(
                service.assetOptions(companyId, transferScope, keyword, page, pageSize),
                TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    @RequiresPerm("deed.ownershipTransfer:view")
    public ApiResponse<OwnershipTransferView> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id), TraceIdUtil.get());
    }

    @PostMapping
    @RequiresPerm("deed.ownershipTransfer:create")
    @Audited(module = "ownership_transfer", action = "create")
    public ApiResponse<OwnershipTransferView> create(@RequestBody OwnershipTransferInput input) {
        return ApiResponse.ok(service.create(input), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    @RequiresPerm("deed.ownershipTransfer:update")
    @Audited(module = "ownership_transfer", action = "update")
    public ApiResponse<OwnershipTransferView> update(
            @PathVariable Long id, @RequestBody OwnershipTransferInput input) {
        return ApiResponse.ok(service.update(id, input), TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("deed.ownershipTransfer:delete")
    @Audited(module = "ownership_transfer", action = "delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    /** 生效：把每个资产的对应公司字段改成新公司。**不可逆**，前端需二次确认。 */
    @PostMapping("/{id}/effect")
    @RequiresPerm("deed.ownershipTransfer:update")
    @Audited(module = "ownership_transfer", action = "effect")
    public ApiResponse<OwnershipTransferView> effect(@PathVariable Long id) {
        return ApiResponse.ok(service.effect(id), TraceIdUtil.get());
    }
}
