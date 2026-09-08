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
        return node;
    }
}
