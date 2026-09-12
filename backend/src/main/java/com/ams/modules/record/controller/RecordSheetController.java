package com.ams.modules.record.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.record.RecordOwnerType;
import com.ams.modules.record.dto.RecordSheetRequest;
import com.ams.modules.record.dto.RecordSheetView;
import com.ams.modules.record.service.OwnerResolver;
import com.ams.modules.record.service.RecordSheetService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RequiresPerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后续记录（处置 / 接收 / 来源）的聚合读写接口 —— 设计 §5.2。
 *
 * <p><strong>为什么只有 6 个端点而不是每模块一组 CRUD</strong>：三模块在同一个表单里
 * 一次提交，拆成逐模块 PUT 会出现两条互相覆盖的写入路径（设计 §5.2 的设计取舍）。
 * 聚合写是一个事务，子记录用全量 diff，中途任一条非法则整单不落库（验收第 10 条）。
 *
 * <p><strong>授权分两层</strong>：{@code @RequiresPerm} 判「有没有这个菜单的动作」，
 * {@link OwnerResolver} 判「这个对象在不在你的数据范围内」。前者是注解、由
 * {@code PermissionInterceptor} 执行；后者必须显式调用，注解做不到对象级判定（设计 §5.3）。
 *
 * <p>附件不单独开端点：文件本体走 {@code POST /files/upload}，关联关系内嵌在
 * record-sheet 的请求体里（设计 §5.2 / §4.3）。
 */
@RestController
@RequestMapping("/api/v1")
public class RecordSheetController {

    private final RecordSheetService recordSheetService;
    private final OwnerResolver ownerResolver;

    public RecordSheetController(RecordSheetService recordSheetService, OwnerResolver ownerResolver) {
        this.recordSheetService = recordSheetService;
        this.ownerResolver = ownerResolver;
    }

    // ---- 资产 ----

    @GetMapping("/assets/{assetId}/record-sheet")
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<RecordSheetView> assetSheet(@PathVariable Long assetId) {
        ownerResolver.assertAccessible(RecordOwnerType.ASSET, assetId);
        return ApiResponse.ok(recordSheetService.read(RecordOwnerType.ASSET, assetId), TraceIdUtil.get());
    }

    @PutMapping("/assets/{assetId}/record-sheet")
    @RequiresPerm("asset.ledger:update")
    @Audited(module = "record", action = "save_asset_record_sheet")
    public ApiResponse<RecordSheetView> saveAssetSheet(
            @PathVariable Long assetId, @RequestBody RecordSheetRequest request) {
        ownerResolver.assertAccessible(RecordOwnerType.ASSET, assetId);
        return ApiResponse.ok(
                recordSheetService.save(RecordOwnerType.ASSET, assetId, request), TraceIdUtil.get());
    }

    // ---- 项目 ----

    @GetMapping("/projects/{projectId}/record-sheet")
    @RequiresPerm("asset.project:view")
    public ApiResponse<RecordSheetView> projectSheet(@PathVariable Long projectId) {
        ownerResolver.assertAccessible(RecordOwnerType.PROJECT, projectId);
        return ApiResponse.ok(
                recordSheetService.read(RecordOwnerType.PROJECT, projectId), TraceIdUtil.get());
    }

    @PutMapping("/projects/{projectId}/record-sheet")
    @RequiresPerm("asset.project:update")
    @Audited(module = "record", action = "save_project_record_sheet")
    public ApiResponse<RecordSheetView> saveProjectSheet(
            @PathVariable Long projectId, @RequestBody RecordSheetRequest request) {
        ownerResolver.assertAccessible(RecordOwnerType.PROJECT, projectId);
        return ApiResponse.ok(
                recordSheetService.save(RecordOwnerType.PROJECT, projectId, request), TraceIdUtil.get());
    }

    // ---- 分区 ----
    // 路径嵌套在既有分区资源下，与已上线的分区 CRUD 一致：projectId 参与归属判定，
    // 分区不属于该项目时与「分区不存在」返回完全相同的 404（assertAccessibleInProject）。

    @GetMapping("/projects/{projectId}/zones/{zoneId}/record-sheet")
    @RequiresPerm("asset.project:view")
    public ApiResponse<RecordSheetView> zoneSheet(
            @PathVariable Long projectId, @PathVariable Long zoneId) {
        ownerResolver.assertAccessibleInProject(projectId, zoneId);
        return ApiResponse.ok(recordSheetService.read(RecordOwnerType.ZONE, zoneId), TraceIdUtil.get());
    }

    @PutMapping("/projects/{projectId}/zones/{zoneId}/record-sheet")
    @RequiresPerm("asset.project:update")
    @Audited(module = "record", action = "save_zone_record_sheet")
    public ApiResponse<RecordSheetView> saveZoneSheet(
            @PathVariable Long projectId, @PathVariable Long zoneId,
            @RequestBody RecordSheetRequest request) {
        ownerResolver.assertAccessibleInProject(projectId, zoneId);
        return ApiResponse.ok(
                recordSheetService.save(RecordOwnerType.ZONE, zoneId, request), TraceIdUtil.get());
    }
}
