package com.ams.modules.org.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.dto.OrgGraph;
import com.ams.modules.org.dto.OrgGraphEdge;
import com.ams.modules.org.dto.OrgGraphNode;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 组织架构图谱构建：把公司树、部门、员工统一抽象为「节点 + 类型化边」。
 *
 * <p>节点：{@code company} / {@code department} / {@code employee}
 * <br>边：{@code parent_of}（公司→子公司）、{@code has_dept}（公司→部门）、
 * {@code has_sub_dept}（部门→子部门）、{@code member_of}（部门→员工）
 *
 * <p>支持按根节点子树过滤与层级截断，供前端画布渲染与图谱遍历查询。
 */
@Service
public class OrgGraphService {

    private final CompanyTreeService companyTreeService;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;

    public OrgGraphService(
            CompanyTreeService companyTreeService,
            DepartmentMapper departmentMapper,
            UserMapper userMapper) {
        this.companyTreeService = companyTreeService;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
    }

    /**
     * 构建组织架构图谱。
     *
     * @param rootId 根公司 ID；为空时取图谱根（母公司）
     * @param depth  层级截断：1=仅公司，2=+部门，3=+员工；null/小于1 视为 3
     */
    public OrgGraph buildGraph(Long rootId, Integer depth) {
        int maxDepth = (depth == null || depth < 1) ? 3 : Math.min(depth, 3);

        List<Company> companies = companyTreeService.listCompanies();
        Map<Long, Company> companyById = new HashMap<>();
        companies.forEach(c -> companyById.put(c.getId(), c));

        // 根节点：指定 rootId 不存在时报错，未指定时取母公司
        Company root = null;
        if (rootId != null) {
            root = companyById.get(rootId);
            if (root == null) {
                throw new AppException(ErrorCode.NOT_FOUND, "公司不存在");
            }
        } else {
            for (Company c : companies) {
                if (c.getParentId() == null) {
                    root = c;
                    break;
                }
            }
        }

        // 子树范围内的公司集合（rootId 指定时按子树过滤）
        Set<Long> scopeCompanyIds = rootId == null
                ? new LinkedHashSet<>(companyById.keySet())
                : companyTreeService.descendantIds(rootId);
        if (rootId != null && root != null) {
            scopeCompanyIds.add(root.getId());
        }

        List<Department> departments = departmentMapper.selectList(
                new LambdaQueryWrapper<Department>()
                        .orderByAsc(Department::getSort)
                        .orderByAsc(Department::getId));
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>().orderByAsc(User::getId));

        OrgGraph graph = new OrgGraph();
        Set<String> nodeIds = new LinkedHashSet<>();

        // ---- 公司节点 + parent_of 边 ----
        for (Company c : companies) {
            if (!scopeCompanyIds.contains(c.getId())) {
                continue;
            }
            OrgGraphNode node = OrgGraphNode.of(OrgGraphNode.TYPE_COMPANY, c.getId(), c.getName())
                    .attr("shortName", c.getShortName())
                    .attr("companyType", c.getCompanyType())
                    .attr("address", c.getAddress())
                    .attr("phone", c.getPhone())
                    .attr("createdAt", c.getCreatedAt())
                    .attr("updatedAt", c.getUpdatedAt())
                    .attr("isRoot", c.getParentId() == null);
            node.setStatus(c.getStatus());
            node.setParentId(c.getParentId() == null ? null : "company:" + c.getParentId());
            node.setSubtitle(c.getShortName() != null ? c.getShortName() : c.getCompanyType());
            if (nodeIds.add(node.getId())) {
                graph.getNodes().add(node);
            }
            if (c.getParentId() != null && scopeCompanyIds.contains(c.getParentId())) {
                graph.getEdges().add(new OrgGraphEdge(
                        "parent_of:" + c.getParentId() + "-" + c.getId(),
                        "company:" + c.getParentId(),
                        "company:" + c.getId(),
                        "parent_of",
                        "子公司"));
            }
        }

        // ---- 部门节点 + has_dept / has_sub_dept 边 ----
        Set<Long> deptIds = new LinkedHashSet<>();
        if (maxDepth >= 2) {
            for (Department d : departments) {
                if (!scopeCompanyIds.contains(d.getCompanyId())) {
                    continue;
                }
                OrgGraphNode node = OrgGraphNode.of(
                                OrgGraphNode.TYPE_DEPARTMENT, d.getId(), d.getName())
                        .attr("type", d.getType())
                        .attr("leaderId", d.getLeaderId())
                        .attr("remark", d.getRemark())
                        .attr("createdAt", d.getCreatedAt())
                        .attr("updatedAt", d.getUpdatedAt());
                node.setStatus(d.getStatus());
                node.setCompanyId(d.getCompanyId());
                node.setSubtitle(d.getType());
                if (d.getParentId() != null) {
                    node.setParentId("department:" + d.getParentId());
                } else {
                    node.setParentId("company:" + d.getCompanyId());
                }
                if (nodeIds.add(node.getId())) {
                    graph.getNodes().add(node);
                }
                deptIds.add(d.getId());

                if (d.getParentId() != null && departmentExists(departments, d.getParentId())) {
                    graph.getEdges().add(new OrgGraphEdge(
                            "has_sub_dept:" + d.getParentId() + "-" + d.getId(),
                            "department:" + d.getParentId(),
                            "department:" + d.getId(),
                            "has_sub_dept",
                            "子部门"));
                } else {
                    graph.getEdges().add(new OrgGraphEdge(
                            "has_dept:" + d.getCompanyId() + "-" + d.getId(),
                            "company:" + d.getCompanyId(),
                            "department:" + d.getId(),
                            "has_dept",
                            "部门"));
                }
            }
        }

        // ---- 员工节点 + member_of 边 ----
        if (maxDepth >= 3) {
            for (User u : users) {
                // 员工归属：优先部门，其次公司；两者都不在范围内则跳过
                boolean inScope = (u.getDepartmentId() != null && deptIds.contains(u.getDepartmentId()))
                        || (u.getCompanyId() != null && scopeCompanyIds.contains(u.getCompanyId()));
                if (!inScope) {
                    continue;
                }
                String displayName = StringUtils.hasText(u.getName()) ? u.getName() : u.getUsername();
                OrgGraphNode node = OrgGraphNode.of(
                                OrgGraphNode.TYPE_EMPLOYEE, u.getId(), displayName)
                        .attr("username", u.getUsername())
                        .attr("phone", u.getPhone())
                        .attr("departmentId", u.getDepartmentId())
                        .attr("lastLoginAt", u.getLastLoginAt());
                node.setStatus(u.getStatus());
                node.setCompanyId(u.getCompanyId());
                node.setSubtitle(u.getUsername());
                if (u.getDepartmentId() != null) {
                    node.setParentId("department:" + u.getDepartmentId());
                } else if (u.getCompanyId() != null) {
                    node.setParentId("company:" + u.getCompanyId());
                }
                if (!nodeIds.add(node.getId())) {
                    continue;
                }
                graph.getNodes().add(node);
                if (u.getDepartmentId() != null && deptIds.contains(u.getDepartmentId())) {
                    graph.getEdges().add(new OrgGraphEdge(
                            "member_of:" + u.getDepartmentId() + "-" + u.getId(),
                            "department:" + u.getDepartmentId(),
                            "employee:" + u.getId(),
                            "member_of",
                            "员工"));
                } else if (u.getCompanyId() != null) {
                    graph.getEdges().add(new OrgGraphEdge(
                            "employs:" + u.getCompanyId() + "-" + u.getId(),
                            "company:" + u.getCompanyId(),
                            "employee:" + u.getId(),
                            "employs",
                            "直属"));
                }
            }
        }

        // ---- 统计 ----
        Map<String, Integer> byType = new LinkedHashMap<>();
        graph.getNodes().forEach(n -> byType.merge(n.getNodeType(), 1, Integer::sum));
        graph.putStat("companyCount", byType.getOrDefault(OrgGraphNode.TYPE_COMPANY, 0));
        graph.putStat("departmentCount", byType.getOrDefault(OrgGraphNode.TYPE_DEPARTMENT, 0));
        graph.putStat("employeeCount", byType.getOrDefault(OrgGraphNode.TYPE_EMPLOYEE, 0));
        graph.putStat("nodeCount", graph.getNodes().size());
        graph.putStat("edgeCount", graph.getEdges().size());
        graph.putStat("rootId", root == null ? null : root.getId());
        graph.putStat("rootName", root == null ? null : root.getName());
        graph.putStat("depth", maxDepth);
        return graph;
    }

    /**
     * 邻居查询：返回某节点直接相连的节点（含边方向与类型）。
     */
    public Map<String, Object> neighbors(String nodeType, Long bizId) {
        if (!StringUtils.hasText(nodeType) || bizId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "节点类型与 ID 不能为空");
        }
        OrgGraph full = buildGraph(null, 3);
        String nodeId = nodeType + ":" + bizId;

        List<Map<String, Object>> incoming = new ArrayList<>();
        List<Map<String, Object>> outgoing = new ArrayList<>();
        for (OrgGraphEdge edge : full.getEdges()) {
            if (edge.getTarget().equals(nodeId)) {
                incoming.add(edgeView(edge, "in"));
            }
            if (edge.getSource().equals(nodeId)) {
                outgoing.add(edgeView(edge, "out"));
            }
        }

        OrgGraphNode self = full.getNodes().stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst()
                .orElse(null);
        if (self == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "节点不存在");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node", self);
        result.put("incoming", incoming);
        result.put("outgoing", outgoing);
        result.put("degree", incoming.size() + outgoing.size());
        return result;
    }

    /** 面包屑：root → 当前公司。 */
    public List<Company> ancestors(Long companyId) {
        List<Long> ids = companyTreeService.ancestorIds(companyId);
        if (ids.isEmpty()) {
            throw new AppException(ErrorCode.NOT_FOUND, "公司不存在");
        }
        Map<Long, Company> byId = new HashMap<>();
        companyTreeService.listCompanies().forEach(c -> byId.put(c.getId(), c));
        List<Company> path = new ArrayList<>();
        for (Long id : ids) {
            Company c = byId.get(id);
            if (c != null) {
                path.add(c);
            }
        }
        return path;
    }

    private Map<String, Object> edgeView(OrgGraphEdge edge, String direction) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("edgeId", edge.getId());
        view.put("edgeType", edge.getEdgeType());
        view.put("label", edge.getLabel());
        view.put("direction", direction);
        view.put("nodeId", "out".equals(direction) ? edge.getTarget() : edge.getSource());
        return view;
    }

    private boolean departmentExists(List<Department> departments, Long id) {
        return departments.stream().anyMatch(d -> d.getId().equals(id));
    }
}
