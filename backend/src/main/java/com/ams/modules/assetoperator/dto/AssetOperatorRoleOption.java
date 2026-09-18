package com.ams.modules.assetoperator.dto;

import lombok.Data;

/**
 * 角色下拉候选（V60）。
 *
 * <p><b>为什么不复用 {@code GET /system/roles}</b>：那个端点要求 {@code system.role:view}
 * （角色与权限矩阵属系统管理面）。让维护运营人员档案的人因为它选不到角色，
 * 与人员下拉是同一类问题。这里只返回**已启用**角色，并只取展示所需的三个字段。
 *
 * <p>注意本模块的「角色」只登记、不授权（见 {@code AssetOperator} 的类注释）。
 */
@Data
public class AssetOperatorRoleOption {

    private Long roleId;

    /** 角色编码（如 {@code operator}），供前端按码做提示，不用于鉴权判定。 */
    private String code;

    private String name;

    /** 角色的数据范围（all / company / dept / project / self），只读展示用。 */
    private String dataScope;
}
