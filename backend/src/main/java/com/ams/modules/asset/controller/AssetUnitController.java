package com.ams.modules.asset.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.asset.dto.AssetUnitMergeRequest;
import com.ams.modules.asset.dto.AssetUnitSplitRequest;
import com.ams.modules.asset.entity.AssetUnit;
import com.ams.modules.asset.service.AssetUnitService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.OwnershipResolver;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.RequiresPerm;
import com.ams.platform.security.SecurityUtils;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计租单元接口：列表、拆分、合并（FR-MDM-001~004、FR-OPS-006）。
 *
 * <p>这是「资产支持部分租赁」的业务落点：单元拆分是部分出租 / 部分占用的唯一正确表达方式
 * （ADR-0019 决策 A1），合并是其逆操作，用于撤销误拆（ADR-0021 规则 S12）。
 *
 * <p><b>权限口径</b>：与既有的资产层 {@code /assets/{id}/split}、{@code /assets/merge} 保持一致，
 * 复用 {@code asset.ledger:view} / {@code asset.ledger:update}，不新开权限码。
 * 原因是 {@code PermissionRegistry} 会校验注解里的菜单码在 {@code menu} 表中真实存在，
 * 数据库里没有 {@code asset.unit} 这个菜单，新开码会让所有角色 403 且启动即失败。
 * 若日后确需独立权限点，须先补 menu 迁移与角色授权，再改注解。
 *
 * <p><b>数据权限</b>：单元自身不承载公司列，归属只能经 {@code asset_id} 推出。
 * 因此每个写入口都先解析出 {@code assetId} 再断言范围 —— 否则持有任一 unitId
 * 就能操作范围外资产的单元。
 */
@RestController
@RequestMapping("/api/v1")
public class AssetUnitController {

    private final AssetUnitService unitService;
    private final RbacService rbacService;
    private final OwnershipResolver ownershipResolver;

    public AssetUnitController(
            AssetUnitService unitService,
            RbacService rbacService,
            OwnershipResolver ownershipResolver) {
        this.unitService = unitService;
        this.rbacService = rbacService;
        this.ownershipResolver = ownershipResolver;
    }

    /**
     * 资产下的有效计租单元（含物化派生状态 {@code unitStatus}）。
     *
     * <p>只返回未软删单元：拆分后原单元会软删，历史身份留在
     * {@code asset_structure_log} 与 {@code unit_no} 里，不再出现在可操作列表中。
     */
    @GetMapping("/assets/{assetId}/units")
    @RequiresPerm("asset.ledger:view")
    public ApiResponse<List<AssetUnit>> units(@PathVariable Long assetId) {
        assertAsset(assetId);
        return ApiResponse.ok(unitService.listByAsset(assetId), TraceIdUtil.get());
    }

    /**
     * 拆分单元：把一个单元替换为多个更细粒度的单元（原单元软删）。
     *
     * <p>失败原因是可读的（有占用 / 有招租 / 在押 / 面积不守恒 / 占位单元），
     * 由 {@code GlobalExceptionHandler} 原样透出，不在本层吞成通用错误。
     */
    @PostMapping("/assets/units/{unitId}/split")
    @RequiresPerm("asset.ledger:update")
    @Audited(module = "asset", action = "unit_split")
    public ApiResponse<List<AssetUnit>> split(
            @PathVariable Long unitId, @RequestBody AssetUnitSplitRequest body) {
        // 先解析归属再断言范围：unitId 是路径参数，可能指向范围外资产的单元
        assertAsset(unitService.getUnit(unitId).getAssetId());
        return ApiResponse.ok(unitService.split(
                unitId,
                body == null ? null : body.childAreas(),
                body == null ? null : body.remark()), TraceIdUtil.get());
    }

    /** 合并单元：把同一资产下多个空置单元合并为一个（拆分的逆操作）。 */
    @PostMapping("/assets/units/merge")
    @RequiresPerm("asset.ledger:update")
    @Audited(module = "asset", action = "unit_merge")
    public ApiResponse<AssetUnit> merge(@RequestBody AssetUnitMergeRequest body) {
        List<Long> unitIds = body == null ? null : body.unitIds();
        /*
         * 合并会改写每一个源单元，必须逐条确认都在当前账号范围内。
         * 服务层还会再校验"同资产"，但那是业务不变量校验，不能替代数据权限断言。
         */
        if (unitIds != null) {
            for (Long unitId : unitIds) {
                if (unitId != null) {
                    assertAsset(unitService.getUnit(unitId).getAssetId());
                }
            }
        }
        return ApiResponse.ok(unitService.merge(unitIds, body == null ? null : body.remark()),
                TraceIdUtil.get());
    }

    private void assertAsset(Long assetId) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), ownershipResolver.ofAsset(assetId));
    }
}
