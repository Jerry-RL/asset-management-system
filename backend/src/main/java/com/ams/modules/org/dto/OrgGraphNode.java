package com.ams.modules.org.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * 组织架构图谱节点。
 *
 * <p>{@code id} 形如 {@code company:1} / {@code department:8} / {@code employee:17}，
 * 保证跨类型唯一，可直接作为图库的节点标识。
 */
@Data
public class OrgGraphNode {

    public static final String TYPE_COMPANY = "company";
    public static final String TYPE_DEPARTMENT = "department";
    public static final String TYPE_EMPLOYEE = "employee";

    private String id;

    /** company | department | employee */
    private String nodeType;

    /** 业务主键 */
    private Long bizId;

    private String name;

    /** 副标题：公司简称 / 部门类型 / 手机号等 */
    private String subtitle;

    private Integer status;

    /** 上级节点 ID（company.parentId / department.parentId / 部门归属公司的边由 edges 表达） */
    private String parentId;

    /** 所属公司 ID（便于按公司过滤与数据范围判断） */
    private Long companyId;

    /** 展示用扩展属性（公司地址、电话等） */
    private Map<String, Object> attrs = new LinkedHashMap<>();

    public static OrgGraphNode of(String nodeType, Long bizId, String name) {
        OrgGraphNode node = new OrgGraphNode();
        node.setNodeType(nodeType);
        node.setBizId(bizId);
        node.setId(nodeType + ":" + bizId);
        node.setName(name);
        return node;
    }

    public OrgGraphNode attr(String key, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            attrs.put(key, value);
        }
        return this;
    }
}
