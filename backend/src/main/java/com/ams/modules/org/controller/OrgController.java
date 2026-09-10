package com.ams.modules.org.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.org.dto.OrgGraph;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.service.OrgGraphService;
import com.ams.modules.org.service.OrgService;
import com.ams.platform.security.Audited;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织架构接口：公司树、部门、员工图谱（公司 → 部门 → 员工）。
 */
@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

    private final OrgService orgService;
    private final OrgGraphService orgGraphService;

    public OrgController(OrgService orgService, OrgGraphService orgGraphService) {
        this.orgService = orgService;
        this.orgGraphService = orgGraphService;
    }

    // ---- 图谱查询 ----

    /**
     * 组织架构图谱（节点 + 类型化边）。
     *
     * @param rootId 根公司 ID；为空取母公司
     * @param depth  1=仅公司，2=+部门，3=+员工
     */
    @GetMapping("/graph")
    public ApiResponse<OrgGraph> graph(
            @RequestParam(required = false) Long rootId,
            @RequestParam(required = false) Integer depth) {
        return ApiResponse.ok(orgGraphService.buildGraph(rootId, depth), TraceIdUtil.get());
    }

    /** 某节点的邻居（入边/出边 + 边类型），用于图谱遍历。 */
    @GetMapping("/graph/neighbors")
    public ApiResponse<Map<String, Object>> neighbors(
            @RequestParam String nodeType, @RequestParam Long bizId) {
        return ApiResponse.ok(orgGraphService.neighbors(nodeType, bizId), TraceIdUtil.get());
    }

    /** 面包屑：母公司 → 当前公司。 */
    @GetMapping("/companies/{id}/path")
    public ApiResponse<List<Company>> companyPath(@PathVariable Long id) {
        return ApiResponse.ok(orgGraphService.ancestors(id), TraceIdUtil.get());
    }

    /** 子树公司 ID（含自身），供数据范围与级联筛选使用。 */
    @GetMapping("/companies/{id}/descendants")
    public ApiResponse<Set<Long>> descendants(@PathVariable Long id) {
        return ApiResponse.ok(orgService.descendantIds(id), TraceIdUtil.get());
    }

    // ---- 公司 ----

    @GetMapping("/companies")
    public ApiResponse<List<Company>> companies() {
        return ApiResponse.ok(orgService.listCompanies(), TraceIdUtil.get());
    }

    @GetMapping("/companies/{id}")
    public ApiResponse<Company> company(@PathVariable Long id) {
        return ApiResponse.ok(orgService.getCompany(id), TraceIdUtil.get());
    }

    @PostMapping("/companies")
    @Audited(module = "org", action = "create_company")
    public ApiResponse<Company> createCompany(@RequestBody Company company) {
        return ApiResponse.ok(orgService.createCompany(company), TraceIdUtil.get());
    }

    /**
     * 编辑公司。
     *
     * <p>仅当请求体显式携带 {@code parentId} 时才变更上级公司，避免「只改名字/状态」
     * 这类局部更新被误判为「变更为母公司」而把子公司摘挂成根节点。
     */
    @PutMapping("/companies/{id}")
    @Audited(module = "org", action = "update_company")
    public ApiResponse<Company> updateCompany(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Company company = new Company();
        company.setName((String) body.get("name"));
        company.setShortName((String) body.get("shortName"));
        company.setCompanyType((String) body.get("companyType"));
        company.setAddress((String) body.get("address"));
        company.setPhone((String) body.get("phone"));
        company.setSort(intOf(body.get("sort")));
        company.setStatus(intOf(body.get("status")));
        company.setParentId(longOf(body.get("parentId")));
        return ApiResponse.ok(
                orgService.updateCompany(id, company, body.containsKey("parentId")),
                TraceIdUtil.get());
    }

    /** 变更上级公司（带防环校验）。 */
    @PutMapping("/companies/{id}/parent")
    @Audited(module = "org", action = "change_company_parent")
    public ApiResponse<Company> changeParent(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long parentId = body.get("parentId") == null
                ? null
                : Long.valueOf(body.get("parentId").toString());
        return ApiResponse.ok(orgService.changeParent(id, parentId), TraceIdUtil.get());
    }

    /** 启用/停用公司（图谱节点快捷操作，不触发上级变更）。 */
    @PutMapping("/companies/{id}/status")
    @Audited(module = "org", action = "update_company_status")
    public ApiResponse<Company> updateCompanyStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(
                orgService.updateCompanyStatus(id, intOf(body.get("status"))), TraceIdUtil.get());
    }

    @DeleteMapping("/companies/{id}")
    @Audited(module = "org", action = "delete_company")
    public ApiResponse<Void> deleteCompany(@PathVariable Long id) {
        orgService.deleteCompany(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    // ---- 部门 ----

    @GetMapping("/departments")
    public ApiResponse<List<Department>> departments(@RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(orgService.listDepartments(companyId), TraceIdUtil.get());
    }

    @GetMapping("/departments/{id}")
    public ApiResponse<Department> department(@PathVariable Long id) {
        return ApiResponse.ok(orgService.getDepartment(id), TraceIdUtil.get());
    }

    @PostMapping("/departments")
    @Audited(module = "org", action = "create_department")
    public ApiResponse<Department> createDepartment(@RequestBody Department department) {
        return ApiResponse.ok(orgService.createDepartment(department), TraceIdUtil.get());
    }

    @PutMapping("/departments/{id}")
    @Audited(module = "org", action = "update_department")
    public ApiResponse<Department> updateDepartment(
            @PathVariable Long id, @RequestBody Department department) {
        return ApiResponse.ok(orgService.updateDepartment(id, department), TraceIdUtil.get());
    }

    /** 启用/停用部门（图谱节点快捷操作）。 */
    @PutMapping("/departments/{id}/status")
    @Audited(module = "org", action = "update_department_status")
    public ApiResponse<Department> updateDepartmentStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(
                orgService.updateDepartmentStatus(id, intOf(body.get("status"))),
                TraceIdUtil.get());
    }

    @DeleteMapping("/departments/{id}")
    @Audited(module = "org", action = "delete_department")
    public ApiResponse<Void> deleteDepartment(@PathVariable Long id) {
        orgService.deleteDepartment(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    private Integer intOf(Object raw) {
        return raw == null ? null : Integer.valueOf(raw.toString());
    }

    private Long longOf(Object raw) {
        return raw == null ? null : Long.valueOf(raw.toString());
    }
}
