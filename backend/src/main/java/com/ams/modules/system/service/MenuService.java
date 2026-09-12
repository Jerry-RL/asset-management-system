package com.ams.modules.system.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.system.dto.MenuNode;
import com.ams.modules.system.entity.Menu;
import com.ams.modules.system.entity.RolePermission;
import com.ams.modules.system.mapper.MenuMapper;
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
import org.springframework.util.StringUtils;

/**
 * 菜单树（FR-SYS-001）：菜单 CRUD、管理树、当前用户可见树。
 *
 * <h2>两级模型（设计 3.1）</h2>
 * <ul>
 *   <li>{@code dir} 目录：必须有 {@code code}，<strong>禁止 {@code path}</strong>，{@code parent_id} 必须为空；</li>
 *   <li>{@code menu} 菜单：必须有 {@code code} + {@code path}，且必须挂在某个 {@code dir} 下。</li>
 * </ul>
 * 操作（view/create/...）不是菜单节点，来自 {@link com.ams.platform.security.PermissionAction}。
 *
 * <p><strong>{@code code} 是权限命名空间，创建后不可变</strong>：{@code role_permission}
 * 依赖它，改名会让既有授权静默指向不存在的权限点。
 */
@Service
public class MenuService {

    private static final String TYPE_DIR = "dir";
    private static final String TYPE_MENU = "menu";

    private final MenuMapper menuMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public MenuService(MenuMapper menuMapper, RolePermissionMapper rolePermissionMapper) {
        this.menuMapper = menuMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    /** 全部菜单（扁平，含停用），按 sort、id 排序。 */
    public List<Menu> listMenus() {
        return menuMapper.selectList(
                new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSort).orderByAsc(Menu::getId));
    }

    /**
     * 管理页用的完整菜单树：<strong>含停用节点</strong>。
     *
     * <p>与 {@link #menusFor(LoginUser)} 的区别是这里不做权限过滤、也不做状态过滤 ——
     * 管理页要能看到并重新启用已停用节点，否则停用一次就再也点不到了。
     */
    public List<MenuNode> adminTree() {
        return buildTree(listMenus());
    }

    public Menu createMenu(Menu menu) {
        menu.setId(null);
        validate(menu, null);
        if (menu.getStatus() == null) {
            menu.setStatus(1);
        }
        if (menu.getSort() == null) {
            menu.setSort(0);
        }
        menuMapper.insert(menu);
        return menu;
    }

    /**
     * 更新菜单。
     *
     * <p>{@code code} 与 {@code menuType} 一律以库中现值为准：前者是权限命名空间、
     * 后者变更会让子节点关系失效，两者都不在本接口的能力范围内。
     */
    public Menu updateMenu(Long id, Menu menu) {
        Menu existing = menuMapper.selectById(id);
        if (existing == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        menu.setId(id);
        menu.setCode(existing.getCode());
        menu.setMenuType(existing.getMenuType());
        validate(menu, existing);
        if (menu.getStatus() == null) {
            menu.setStatus(existing.getStatus());
        }
        if (menu.getSort() == null) {
            menu.setSort(existing.getSort());
        }
        menuMapper.updateById(menu);
        return menuMapper.selectById(id);
    }

    /**
     * 删除菜单。
     *
     * <p><strong>存在子节点或已被 {@code role_permission} 引用时只能停用，不能删除</strong>
     * （设计 3.3）：直接删除会让角色配置里留下悬空授权。
     */
    public void deleteMenu(Long id) {
        Menu existing = menuMapper.selectById(id);
        if (existing == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        Long children = menuMapper.selectCount(
                new LambdaQueryWrapper<Menu>().eq(Menu::getParentId, id));
        if (children != null && children > 0) {
            throw new AppException(
                    ErrorCode.BAD_REQUEST, "存在下级菜单，只能停用不能删除");
        }
        Long granted = rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getMenuId, id));
        if (granted != null && granted > 0) {
            throw new AppException(
                    ErrorCode.BAD_REQUEST, "该菜单已被角色授权引用，只能停用不能删除");
        }
        menuMapper.deleteById(id);
    }

    /**
     * 校验菜单字段（创建与更新共用）。
     *
     * @param existing 更新时的库中现值；创建传 {@code null}
     */
    private void validate(Menu menu, Menu existing) {
        String type = existing != null ? existing.getMenuType() : menu.getMenuType();
        if (!TYPE_DIR.equals(type) && !TYPE_MENU.equals(type)) {
            throw new AppException(
                    ErrorCode.BAD_REQUEST, "菜单类型只能是 dir（目录）或 menu（菜单）");
        }
        if (!StringUtils.hasText(menu.getName())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "名称不能为空");
        }
        if (existing == null) {
            if (!StringUtils.hasText(menu.getCode())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "编码不能为空");
            }
            if (menuMapper.selectCount(
                    new LambdaQueryWrapper<Menu>().eq(Menu::getCode, menu.getCode())) > 0) {
                throw new AppException(ErrorCode.CONFLICT, "编码已存在");
            }
        }

        if (TYPE_DIR.equals(type)) {
            if (StringUtils.hasText(menu.getPath())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "目录不能配置路由");
            }
            if (menu.getParentId() != null && menu.getParentId() != 0) {
                throw new AppException(ErrorCode.BAD_REQUEST, "目录不能有上级，层级上限为两级");
            }
            // 归一化：避免 0 与 null 两种「无上级」写法让树构建分叉
            menu.setParentId(null);
            return;
        }

        // menu：必须有 path，且必须挂在启用/停用的目录下（目录本身可以暂时停用）
        if (!StringUtils.hasText(menu.getPath())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "菜单必须配置路由");
        }
        if (menu.getParentId() == null || menu.getParentId() == 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "菜单必须选择上级目录");
        }
        Menu parent = menuMapper.selectById(menu.getParentId());
        if (parent == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "上级目录不存在");
        }
        if (!TYPE_DIR.equals(parent.getMenuType())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "上级必须是目录，层级上限为两级");
        }
        if (existing != null && existing.getId().equals(menu.getParentId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "不能把菜单挂在自己之下");
        }
    }

    /**
     * 当前用户可见菜单树（侧边栏数据源）。
     *
     * <h2>可见性口径（设计 4.1）</h2>
     * 菜单可见 ⇔ 角色拥有该 {@code code:view}；目录可见 ⇔ 其下<strong>任一菜单</strong>可见。
     *
     * <p>改造原因：旧实现按「{@code code} + {@code :}」前缀匹配，匹配不到点号分层的子编码
     * （目录 {@code asset} 匹配不到 {@code asset.ledger}），会导致目录全部丢失、子菜单被提升为
     * 顶级；且对「任意动作」放行，会让只勾 {@code create} 的菜单可见却被路由守卫判 403。
     * 现在只认 {@code view}。
     *
     * <p>状态过滤对所有人（含 {@code super_admin}）生效：停用的目录连同其子菜单整组隐藏，
     * 不论子菜单自身状态（设计 3.3）。管理页要看到停用项请走 {@link #adminTree()}。
     */
    public List<MenuNode> menusFor(LoginUser user) {
        List<Menu> all = listMenus();
        Map<Long, Menu> byId = new HashMap<>();
        for (Menu menu : all) {
            byId.put(menu.getId(), menu);
        }
        Set<String> permissions = user == null ? Set.of() : user.permissionSet();
        boolean superAdmin = user != null && user.isSuperAdmin();

        List<Menu> visible = new ArrayList<>();
        Set<Long> visibleDirIds = new HashSet<>();
        for (Menu menu : all) {
            if (!TYPE_MENU.equals(menu.getMenuType()) || !enabled(menu)) {
                continue;
            }
            // 父目录必须存在且启用：停用目录 = 隐藏整组，不论子菜单自身状态
            Menu parent = menu.getParentId() == null ? null : byId.get(menu.getParentId());
            if (parent == null || !TYPE_DIR.equals(parent.getMenuType()) || !enabled(parent)) {
                continue;
            }
            if (!superAdmin && !permissions.contains(menu.getCode() + ":view")) {
                continue;
            }
            visible.add(menu);
            visibleDirIds.add(parent.getId());
        }
        for (Menu dir : all) {
            if (TYPE_DIR.equals(dir.getMenuType()) && enabled(dir) && visibleDirIds.contains(dir.getId())) {
                visible.add(dir);
            }
        }
        return buildTree(visible);
    }

    private boolean enabled(Menu menu) {
        return menu.getStatus() == null || menu.getStatus() == 1;
    }

    /**
     * 构建两级树。
     *
     * <p>只负责「按 parentId 挂载」，不做任何过滤：调用方（{@link #adminTree()} /
     * {@link #menusFor}）已完成各自的裁剪，这里再裁一次会让管理树看不到停用节点。
     */
    private List<MenuNode> buildTree(List<Menu> menus) {
        Map<Long, MenuNode> nodeMap = new HashMap<>();
        List<MenuNode> roots = new ArrayList<>();
        for (Menu menu : menus) {
            if ("button".equals(menu.getMenuType())) {
                continue; // 操作不是菜单节点（设计 3.1）
            }
            nodeMap.put(menu.getId(), MenuNode.from(menu));
        }
        for (Menu menu : menus) {
            MenuNode node = nodeMap.get(menu.getId());
            if (node == null) {
                continue;
            }
            // 父节点未出现在本批数据里时按根节点处理，避免脏数据导致节点整体丢失
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
