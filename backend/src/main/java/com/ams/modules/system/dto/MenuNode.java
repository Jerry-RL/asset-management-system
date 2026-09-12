package com.ams.modules.system.dto;

import com.ams.modules.system.entity.Menu;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 菜单树节点。
 */
@Data
public class MenuNode {

    private Long id;
    private Long parentId;
    private String name;
    private String code;
    private String path;
    private String icon;
    private Integer sort;
    private String menuType;
    /** 状态（1 启用 / 0 停用）。管理树需要它来决定是否显示「随目录停用」标记。 */
    private Integer status;
    private List<MenuNode> children = new ArrayList<>();

    public static MenuNode from(Menu menu) {
        MenuNode node = new MenuNode();
        node.setId(menu.getId());
        node.setParentId(menu.getParentId());
        node.setName(menu.getName());
        node.setCode(menu.getCode());
        node.setPath(menu.getPath());
        node.setIcon(menu.getIcon());
        node.setSort(menu.getSort());
        node.setMenuType(menu.getMenuType());
        node.setStatus(menu.getStatus());
        return node;
    }
}
