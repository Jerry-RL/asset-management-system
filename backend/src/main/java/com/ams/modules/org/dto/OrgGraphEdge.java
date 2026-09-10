package com.ams.modules.org.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 组织架构图谱边。
 *
 * <p>边类型：
 * <ul>
 *   <li>{@code parent_of} 公司 → 子公司</li>
 *   <li>{@code has_dept} 公司 → 部门</li>
 *   <li>{@code has_sub_dept} 部门 → 子部门</li>
 *   <li>{@code member_of} 部门 → 员工</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgGraphEdge {

    private String id;
    private String source;
    private String target;
    private String edgeType;
    private String label;
}
