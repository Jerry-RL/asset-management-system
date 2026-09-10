package com.ams.modules.org.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 组织架构图谱结果：节点 + 边 + 统计，供前端 G6 画布直接消费。
 */
@Data
public class OrgGraph {

    private List<OrgGraphNode> nodes = new ArrayList<>();
    private List<OrgGraphEdge> edges = new ArrayList<>();

    /** 图谱统计：companyCount / departmentCount / employeeCount */
    private Map<String, Object> stats = new LinkedHashMap<>();

    public void putStat(String key, Object value) {
        stats.put(key, value);
    }
}
