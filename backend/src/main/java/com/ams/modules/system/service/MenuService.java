package com.ams.modules.system.service;

import com.ams.modules.system.dto.MenuNode;
import com.ams.modules.system.entity.Menu;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.RolePermission;
import com.ams.modules.system.mapper.MenuMapper;
import com.ams.modules.system.mapper.RoleMapper;
import com.ams.modules.system.mapper.RolePermissionMapper;
import com.ams.platform.security.LoginUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 菜单与角色权限（FR-SYS-001）：菜单树、角色 CRUD、用户菜单。
 */
@Service
public class MenuService {

    private final MenuMapper menuMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public MenuService(
            MenuMapper menuMapper, RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper) {
        this.menuMapper = menuMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    public List<Menu> listMenus() {
        return menuMapper.selectList(
                new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSort).orderByAsc(Menu::getId));
    }

    public Menu createMenu(Menu menu) {
        menuMapper.insert(menu);
        return menu;
    }

    public Menu updateMenu(Long id, Menu menu) {
        menu.setId(id);
        menuMapper.updateById(menu);
        return menuMapper.selectById(id);
    }

    public void deleteMenu(Long id) {
        menuMapper.deleteById(id);
    }

    public List<Role> listRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getId));
    }

    public Role createRole(Role role) {
        roleMapper.insert(role);
        return role;
    }

    public Role updateRole(Long id, Role role) {
        role.setId(id);
        roleMapper.updateById(role);
        return roleMapper.selectById(id);
    }

    public List<RolePermission> listPermissions(Long roleId) {
        return rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
    }

    public void savePermissions(Long roleId, List<RolePermission> permissions) {
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        for (RolePermission rp : permissions) {
            rp.setId(null);
            rp.setRoleId(roleId);
            rolePermissionMapper.insert(rp);
        }
    }

    /**
     * 当前用户可见菜单树（super_admin 全量，否则按权限过滤）。
     */
    public List<MenuNode> menusFor(LoginUser user) {
        List<Menu> all = listMenus();
        List<Menu> visible;
        if (user == null || user.isSuperAdmin()) {
            visible = all;
        } else {
            Set<String> permCodes = user.getPermissions();
            visible = new ArrayList<>();
            for (Menu menu : all) {
                if ("button".equals(menu.getMenuType())) {
                    continue;
                }
                if (menu.getCode() == null
                        || permCodes.stream().anyMatch(p -> p.startsWith(menu.getCode() + ":"))) {
                    visible.add(menu);
                }
            }
        }
        return buildTree(visible);
    }

    private List<MenuNode> buildTree(List<Menu> menus) {
        Map<Long, MenuNode> nodeMap = new HashMap<>();
        List<MenuNode> roots = new ArrayList<>();
        for (Menu menu : menus) {
            nodeMap.put(menu.getId(), MenuNode.from(menu));
        }
        for (Menu menu : menus) {
            MenuNode node = nodeMap.get(menu.getId());
            if (menu.getParentId() == null || menu.getParentId() == 0
                    || !nodeMap.containsKey(menu.getParentId())) {
                roots.add(node);
            } else {
                nodeMap.get(menu.getParentId()).getChildren().add(node);
            }
        }
        return roots;
    }
}
