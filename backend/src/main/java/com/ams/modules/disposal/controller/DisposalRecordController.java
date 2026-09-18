package com.ams.modules.disposal.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.disposal.dto.AssetDisposalRecordView;
import com.ams.modules.disposal.service.AssetDisposalRecordService;
import com.ams.platform.security.RequiresPerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产处置记录接口（V57）。**只读** —— 写入一律由业务动作驱动：
 * 资产级走 {@code POST /api/v1/disposals/{id}/complete}，项目 / 分区级走
 * {@code PUT /projects|zones/{id}/record-sheet} 的处置段，两者在服务层级联写本台账。
 *
 * <p>权限码只有一个：{@code deed.disposalRecord:view}。刻意**不提供** create / update /
 * delete —— 处置记录是「已经发生的事实」的快照，可编辑意味着可以伪造处置历史。
 *
 * <p>菜单归属见 {@code V58__move_disposal_record_menu.sql}：该页从「资产运营」移到
 * 「资债权证记录」下（它是记录 / 台账，而不是发起操作的入口），权限码随之由
 * {@code operation.disposalRecord} 改为 {@code deed.disposalRecord}。
 * 注解里的码必须与 {@code menu.code} 逐字一致，否则 {@code PermissionRegistry} 启动即抛异常终止。
 */
@RestController
@RequestMapping("/api/v1/disposal-records")
public class DisposalRecordController {

    private final AssetDisposalRecordService service;

    public DisposalRecordController(AssetDisposalRecordService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPerm("deed.disposalRecord:view")
    public ApiResponse<PageResult<AssetDisposalRecordView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long fromCompanyId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String disposalType,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(
                service.page(page, pageSize, fromCompanyId, targetType, disposalType, keyword),
                TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    @RequiresPerm("deed.disposalRecord:view")
    public ApiResponse<AssetDisposalRecordView> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id), TraceIdUtil.get());
    }
}
