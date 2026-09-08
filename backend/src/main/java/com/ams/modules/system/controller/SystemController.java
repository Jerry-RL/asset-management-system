package com.ams.modules.system.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.system.dto.MenuNode;
import com.ams.modules.system.entity.Menu;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.RolePermission;
import com.ams.modules.system.service.MenuService;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.SecurityUtils;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理接口：菜单、角色、权限、当前用户（FR-SYS-001）。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final MenuService menuService;

    public SystemController(MenuService menuService) {
        this.menuService = menuService;
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
}
