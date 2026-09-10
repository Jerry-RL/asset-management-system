package com.ams.modules.system.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.system.dto.MenuNode;
import com.ams.modules.system.dto.SysDictRelationBatch;
import com.ams.modules.system.entity.Menu;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.RolePermission;
import com.ams.modules.system.entity.SysDictItem;
import com.ams.modules.system.entity.SysDictModule;
import com.ams.modules.system.entity.SysDictRelation;
import com.ams.modules.system.entity.SysDictType;
import com.ams.modules.system.service.MenuService;
import com.ams.modules.system.service.SystemDictService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.SecurityUtils;
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
 * 系统管理接口：菜单、角色、权限、当前用户、系统字典（FR-SYS-001）。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final MenuService menuService;
    private final SystemDictService systemDictService;

    public SystemController(MenuService menuService, SystemDictService systemDictService) {
        this.menuService = menuService;
        this.systemDictService = systemDictService;
    }

    @GetMapping("/me")
    public ApiResponse<LoginUser> me() {
        return ApiResponse.ok(SecurityUtils.current(), TraceIdUtil.get());
    }

    @GetMapping("/menus")
    public ApiResponse<List<MenuNode>> menus() {
        return ApiResponse.ok(menuService.menusFor(SecurityUtils.current()), TraceIdUtil.get());
    }

    @GetMapping("/menus/all")
    public ApiResponse<List<Menu>> allMenus() {
        return ApiResponse.ok(menuService.listMenus(), TraceIdUtil.get());
    }

    @PostMapping("/menus")
    public ApiResponse<Menu> createMenu(@RequestBody Menu menu) {
        return ApiResponse.ok(menuService.createMenu(menu), TraceIdUtil.get());
    }

    @PutMapping("/menus/{id}")
    public ApiResponse<Menu> updateMenu(@PathVariable Long id, @RequestBody Menu menu) {
        return ApiResponse.ok(menuService.updateMenu(id, menu), TraceIdUtil.get());
    }

    @DeleteMapping("/menus/{id}")
    public ApiResponse<Void> deleteMenu(@PathVariable Long id) {
        menuService.deleteMenu(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @GetMapping("/roles")
    public ApiResponse<List<Role>> roles() {
        return ApiResponse.ok(menuService.listRoles(), TraceIdUtil.get());
    }

    @PostMapping("/roles")
    public ApiResponse<Role> createRole(@RequestBody Role role) {
        return ApiResponse.ok(menuService.createRole(role), TraceIdUtil.get());
    }

    @PutMapping("/roles/{id}")
    public ApiResponse<Role> updateRole(@PathVariable Long id, @RequestBody Role role) {
        return ApiResponse.ok(menuService.updateRole(id, role), TraceIdUtil.get());
    }

    @GetMapping("/roles/{roleId}/permissions")
    public ApiResponse<List<RolePermission>> permissions(@PathVariable Long roleId) {
        return ApiResponse.ok(menuService.listPermissions(roleId), TraceIdUtil.get());
    }

    @PostMapping("/roles/{roleId}/permissions")
    public ApiResponse<Void> savePermissions(
            @PathVariable Long roleId, @RequestBody List<RolePermission> permissions) {
        menuService.savePermissions(roleId, permissions);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    // ---- 系统字典（模块 / 字典 / 字典项） ----

    /** 完整字典树：左侧模块、右侧字典 Tab 及字典项一次性返回。 */
    @GetMapping("/dict/tree")
    public ApiResponse<List<SysDictModule>> dictTree() {
        return ApiResponse.ok(systemDictService.tree(), TraceIdUtil.get());
    }

    @GetMapping("/dict/modules")
    public ApiResponse<List<SysDictModule>> dictModules() {
        return ApiResponse.ok(systemDictService.listModules(), TraceIdUtil.get());
    }

    @PostMapping("/dict/modules")
    @Audited(module = "system", action = "dict_module_create")
    public ApiResponse<SysDictModule> createDictModule(@RequestBody SysDictModule module) {
        return ApiResponse.ok(systemDictService.createModule(module), TraceIdUtil.get());
    }

    @PutMapping("/dict/modules/{id}")
    @Audited(module = "system", action = "dict_module_update")
    public ApiResponse<SysDictModule> updateDictModule(
            @PathVariable Long id, @RequestBody SysDictModule module) {
        return ApiResponse.ok(systemDictService.updateModule(id, module), TraceIdUtil.get());
    }

    @DeleteMapping("/dict/modules/{id}")
    @Audited(module = "system", action = "dict_module_delete")
    public ApiResponse<Void> deleteDictModule(@PathVariable Long id) {
        systemDictService.deleteModule(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @GetMapping("/dict/types")
    public ApiResponse<List<SysDictType>> dictTypes(@RequestParam(required = false) Long moduleId) {
        return ApiResponse.ok(systemDictService.listTypes(moduleId), TraceIdUtil.get());
    }

    @PostMapping("/dict/types")
    @Audited(module = "system", action = "dict_type_create")
    public ApiResponse<SysDictType> createDictType(@RequestBody SysDictType type) {
        return ApiResponse.ok(systemDictService.createType(type), TraceIdUtil.get());
    }

    @PutMapping("/dict/types/{id}")
    @Audited(module = "system", action = "dict_type_update")
    public ApiResponse<SysDictType> updateDictType(
            @PathVariable Long id, @RequestBody SysDictType type) {
        return ApiResponse.ok(systemDictService.updateType(id, type), TraceIdUtil.get());
    }

    @DeleteMapping("/dict/types/{id}")
    @Audited(module = "system", action = "dict_type_delete")
    public ApiResponse<Void> deleteDictType(@PathVariable Long id) {
        systemDictService.deleteType(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    /**
     * 字典项查询。
     *
     * <p>传入 {@code parentCode + parentValue} 时按「字典项级级联」过滤：
     * 父字典该取值下配置了级联规则，则只返回规则内的字典项；未配置则不限制。
     */
    @GetMapping("/dict/items")
    public ApiResponse<List<SysDictItem>> dictItems(
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String parentCode,
            @RequestParam(required = false) String parentValue) {
        return ApiResponse.ok(
                systemDictService.listItems(typeId, code, parentCode, parentValue), TraceIdUtil.get());
    }

    @PostMapping("/dict/items")
    @Audited(module = "system", action = "dict_item_create")
    public ApiResponse<SysDictItem> createDictItem(@RequestBody SysDictItem item) {
        return ApiResponse.ok(systemDictService.createItem(item), TraceIdUtil.get());
    }

    @PutMapping("/dict/items/{id}")
    @Audited(module = "system", action = "dict_item_update")
    public ApiResponse<SysDictItem> updateDictItem(
            @PathVariable Long id, @RequestBody SysDictItem item) {
        return ApiResponse.ok(systemDictService.updateItem(id, item), TraceIdUtil.get());
    }

    @DeleteMapping("/dict/items/{id}")
    @Audited(module = "system", action = "dict_item_delete")
    public ApiResponse<Void> deleteDictItem(@PathVariable Long id) {
        systemDictService.deleteItem(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    // ---- 字典关联（字典 / 字典项 → 另一字典的若干个字典值） ----

    @GetMapping("/dict/relations")
    public ApiResponse<List<SysDictRelation>> dictRelations(
            @RequestParam(required = false) Long sourceTypeId,
            @RequestParam(required = false) Long targetTypeId) {
        return ApiResponse.ok(systemDictService.listRelations(sourceTypeId, targetTypeId), TraceIdUtil.get());
    }

    /** 关联查询：按「父字典项 + 目标字典」分组返回该字典挂接的全部关联值。 */
    @GetMapping("/dict/relations/grouped")
    public ApiResponse<Map<String, Object>> dictRelationsGrouped(@RequestParam Long sourceTypeId) {
        return ApiResponse.ok(systemDictService.relationsOfType(sourceTypeId), TraceIdUtil.get());
    }

    /** 批量替换某字典的全部关联；未出现的目标字典表示不建立关联。 */
    @PutMapping("/dict/relations")
    @Audited(module = "system", action = "dict_relation_save")
    public ApiResponse<List<SysDictRelation>> saveDictRelations(@RequestBody SysDictRelationBatch batch) {
        return ApiResponse.ok(systemDictService.replaceRelations(batch), TraceIdUtil.get());
    }

    @DeleteMapping("/dict/relations/{id}")
    @Audited(module = "system", action = "dict_relation_delete")
    public ApiResponse<Void> deleteDictRelation(@PathVariable Long id) {
        systemDictService.deleteRelation(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }
}
