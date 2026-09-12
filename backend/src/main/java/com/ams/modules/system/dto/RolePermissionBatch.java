package com.ams.modules.system.dto;

import java.util.List;
import lombok.Data;

/**
 * 批量替换角色权限的请求体（设计 4.5 的 {@code PUT /system/roles/{roleId}/permissions}）。
 *
 * <p>按 {@code menuId} 提交而不是 {@code menuCode}：{@code menu_id} 是权威判定依据，
 * {@code menuCode} 只是冗余列，由服务端从 {@code menuId} 解析回填 —— 否则客户端
 * 可以提交任意 code 造成「矩阵显示 A、实际判 B」。
 */
@Data
public class RolePermissionBatch {

    private List<Item> items;

    @Data
    public static class Item {
        private Long menuId;
        private List<String> actions;
    }
}
