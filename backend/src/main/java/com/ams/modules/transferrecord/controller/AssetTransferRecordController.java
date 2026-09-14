package com.ams.modules.transferrecord.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.transferrecord.dto.AssetTransferRecordInput;
import com.ams.modules.transferrecord.dto.AssetTransferRecordView;
import com.ams.modules.transferrecord.dto.TransferRecordAssetOption;
import com.ams.modules.transferrecord.service.AssetTransferRecordService;
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
 * 资产调拨记录接口（V55）。
 *
 * <p>权限码固定 4 个：{@code deed.transferRecord:view|create|update|delete}。
 * 「生效」用 {@code :update} 而不是 {@code :approve}：本期没有审批环节，而仓内
 * {@code :approve} 特指「审批别人的单」（处置 / 占用 / 自用）；仓内推进状态机一律用
 * {@code :update}（{@code operation.disposal} 的 execute / complete 即是）。
 */
@RestController
@RequestMapping("/api/v1/asset-transfer-records")
public class AssetTransferRecordController {

    private final AssetTransferRecordService service;

    public AssetTransferRecordController(AssetTransferRecordService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPerm("deed.transferRecord:view")
    public ApiResponse<PageResult<AssetTransferRecordView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(
                service.page(page, pageSize, status, companyId, keyword), TraceIdUtil.get());
    }

    /**
     * 资产下拉（按所属公司联动过滤）。
     *
     * <p><b>必须声明在 {@code /{id}} 之前</b>：否则「asset-options」会被 {@code @PathVariable}
     * 抢匹配，然后因无法转成 {@code Long} 直接 500。
     */
    @GetMapping("/asset-options")
    @RequiresPerm("deed.transferRecord:view")
    public ApiResponse<PageResult<TransferRecordAssetOption>> assetOptions(
            @RequestParam Long companyId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long pageSize) {
        return ApiResponse.ok(
                service.assetOptions(companyId, keyword, page, pageSize), TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    @RequiresPerm("deed.transferRecord:view")
    public ApiResponse<AssetTransferRecordView> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id), TraceIdUtil.get());
    }

    @PostMapping
    @RequiresPerm("deed.transferRecord:create")
    @Audited(module = "asset_transfer_record", action = "create")
    public ApiResponse<AssetTransferRecordView> create(@RequestBody AssetTransferRecordInput input) {
        return ApiResponse.ok(service.create(input), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    @RequiresPerm("deed.transferRecord:update")
    @Audited(module = "asset_transfer_record", action = "update")
    public ApiResponse<AssetTransferRecordView> update(
            @PathVariable Long id, @RequestBody AssetTransferRecordInput input) {
        return ApiResponse.ok(service.update(id, input), TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("deed.transferRecord:delete")
    @Audited(module = "asset_transfer_record", action = "delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    /** 生效：把每个资产的责任部门 / 责任人改成单上的新值。**不可逆**，前端需二次确认。 */
    @PostMapping("/{id}/effect")
    @RequiresPerm("deed.transferRecord:update")
    @Audited(module = "asset_transfer_record", action = "effect")
    public ApiResponse<AssetTransferRecordView> effect(@PathVariable Long id) {
        return ApiResponse.ok(service.effect(id), TraceIdUtil.get());
    }
}
