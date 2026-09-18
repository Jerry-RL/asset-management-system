package com.ams.modules.assetoperator.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.assetoperator.dto.AssetOperatorInput;
import com.ams.modules.assetoperator.dto.AssetOperatorRoleOption;
import com.ams.modules.assetoperator.dto.AssetOperatorScopeOption;
import com.ams.modules.assetoperator.dto.AssetOperatorUserOption;
import com.ams.modules.assetoperator.dto.AssetOperatorView;
import com.ams.modules.assetoperator.service.AssetOperatorService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RequiresPerm;
import java.util.List;
import java.util.Map;
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
 * 资产运营人员管理接口（V60，FR-OP-001 扩展）。
 *
 * <p>权限码固定 4 个：{@code ops.assetOperator:view|create|update|delete}，
 * 与 {@code menu.code = 'ops.assetOperator'} 逐字一致（不一致时 {@code PermissionRegistry}
 * 启动即抛异常终止 —— 那类错字会让接口对所有角色 403 且不报错）。
 *
 * <p><b>三个选项端点为什么单独开</b>：人员维护（{@code org.user:view}）、角色权限
 * （{@code system.role:view}）、资产台账（{@code asset.ledger:view}）是三个**不同**的菜单
 * 权限，而本页面同时要做「人员 / 角色 / 范围」三种选择。复用既有端点意味着维护运营人员档案
 * 的人只要缺其中任意一个权限，那个下拉就永远是空的。选项端点一律用本模块的 {@code :view}
 * 声明（与 V55 调拨记录、V56 抵押记录另开 options 端点同一取舍）。
 *
 * <p>路径顺序：{@code /user-options} 等字面量段**必须声明在 {@code /{id}} 之前** ——
 * 否则会被 {@code @PathVariable} 抢匹配，然后因无法转成 {@code Long} 直接 500。
 */
@RestController
@RequestMapping("/api/v1/asset-operators")
public class AssetOperatorController {

    private final AssetOperatorService service;

    public AssetOperatorController(AssetOperatorService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPerm("ops.assetOperator:view")
    public ApiResponse<PageResult<AssetOperatorView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return ApiResponse.ok(
                service.page(page, pageSize, keyword, status), TraceIdUtil.get());
    }

    /** 人员下拉：按公司 / 部门联动过滤，支持姓名 / 手机 / 账号搜索。 */
    @GetMapping("/user-options")
    @RequiresPerm("ops.assetOperator:view")
    public ApiResponse<PageResult<AssetOperatorUserOption>> userOptions(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long pageSize) {
        return ApiResponse.ok(
                service.userOptions(keyword, companyId, departmentId, page, pageSize),
                TraceIdUtil.get());
    }

    /** 角色下拉：已启用角色。 */
    @GetMapping("/role-options")
    @RequiresPerm("ops.assetOperator:view")
    public ApiResponse<List<AssetOperatorRoleOption>> roleOptions() {
        return ApiResponse.ok(service.roleOptions(), TraceIdUtil.get());
    }

    /** 运营范围下拉：{@code scopeType} 为 project / zone / asset，必须带公司。 */
    @GetMapping("/scope-options")
    @RequiresPerm("ops.assetOperator:view")
    public ApiResponse<PageResult<AssetOperatorScopeOption>> scopeOptions(
            @RequestParam String scopeType,
            @RequestParam Long companyId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long pageSize) {
        return ApiResponse.ok(
                service.scopeOptions(scopeType, companyId, projectId, keyword, page, pageSize),
                TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    @RequiresPerm("ops.assetOperator:view")
    public ApiResponse<AssetOperatorView> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id), TraceIdUtil.get());
    }

    @PostMapping
    @RequiresPerm("ops.assetOperator:create")
    @Audited(module = "asset_operator", action = "create")
    public ApiResponse<AssetOperatorView> create(@RequestBody AssetOperatorInput input) {
        return ApiResponse.ok(service.create(input), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    @RequiresPerm("ops.assetOperator:update")
    @Audited(module = "asset_operator", action = "update")
    public ApiResponse<AssetOperatorView> update(
            @PathVariable Long id, @RequestBody AssetOperatorInput input) {
        return ApiResponse.ok(service.update(id, input), TraceIdUtil.get());
    }

    /** 启用 / 停用（档案保留，历史运营范围不丢）。 */
    @PutMapping("/{id}/status")
    @RequiresPerm("ops.assetOperator:update")
    @Audited(module = "asset_operator", action = "update_status")
    public ApiResponse<AssetOperatorView> updateStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer status = body == null || body.get("status") == null
                ? null
                : Integer.valueOf(body.get("status").toString());
        return ApiResponse.ok(service.updateStatus(id, status), TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("ops.assetOperator:delete")
    @Audited(module = "asset_operator", action = "delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
}
