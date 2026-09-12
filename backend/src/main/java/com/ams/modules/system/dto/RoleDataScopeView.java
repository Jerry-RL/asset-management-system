package com.ams.modules.system.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 角色数据范围视图（设计 4.5 的 {@code GET /system/roles/{roleId}/data-scope}）。
 *
 * <p><strong>排除清单的语义与全站其他授权相反：勾选即排除</strong>，命中后连同该公司的
 * 整棵下级子树一起扣除。前端必须用三态区分「基线内 / 显式排除 / 继承排除」，
 * 因此这里同时下发默认范围描述与已排除公司，而不是只给一个布尔值。
 */
@Data
@AllArgsConstructor
public class RoleDataScopeView {

    private Long roleId;

    /** 角色配置的取值（all/company/dept/project/self）。 */
    private String dataScope;

    /**
     * 该取值**实际生效**的可见范围描述。
     *
     * <p>dept/project/self 本期退化为公司级，描述里已写明，避免管理员误以为已限制范围。
     */
    private String effectiveScope;

    /**
     * 本期**未实现、已退化**的取值列表。
     *
     * <p>前端据此在表单上提示；为空表示该角色的取值无需提示。
     */
    private List<String> degradedScopes;

    /** 显式排除的公司 id（未展开子树，即管理员勾选的原值）。 */
    private List<Long> excludedCompanyIds;

    /** 候选公司树（仅启用公司，按树序，父在子前）。 */
    private List<CompanyNode> companies;

    /** 不可勾选的公司 id：调用者自己所属公司（排除它会把自己锁在门外）。 */
    private List<Long> lockedCompanyIds;

    @Data
    @AllArgsConstructor
    public static class CompanyNode {
        private Long id;
        private String name;
        private String shortName;
        private Long parentId;
    }
}
