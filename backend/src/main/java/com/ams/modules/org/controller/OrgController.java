package com.ams.modules.org.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.org.dto.OrgGraph;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.service.OrgGraphService;
import com.ams.modules.org.service.OrgService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.RequiresPerm;
import com.ams.platform.security.SecurityUtils;
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
 *
 * <h2>数据范围（设计 6.1）</h2>
 * 列表已由 {@code OrgService} 接入 {@code companyScope}；<strong>按主键取详情、以及全部写路径
 * 都必须在此做对象级断言</strong> —— 列表过滤挡不住「持有 id 直接请求」这条越权路径。
 * 新建公司按 {@code parentId} 判定归属：不填上级（新建根公司）时受限账号一律拒绝，
 * 否则任何人都能在范围外造出一棵新树。
 */
@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

    private final OrgService orgService;
    private final OrgGraphService orgGraphService;
    private final RbacService rbacService;

    public OrgController(
            OrgService orgService, OrgGraphService orgGraphService, RbacService rbacService) {
        this.orgService = orgService;
        this.orgGraphService = orgGraphService;
        this.rbacService = rbacService;
    }

    // ---- 图谱查询 ----

    /**
     * 组织架构图谱（节点 + 类型化边）。
     *
     * @param rootId 根公司 ID；为空取母公司
     * @param depth  1=仅公司，2=+部门，3=+员工
     */
    @GetMapping("/graph")
    @RequiresPerm("org.structure:view")
    public ApiResponse<OrgGraph> graph(
            @RequestParam(required = false) Long rootId,
            @RequestParam(required = false) Integer depth) {
        return ApiResponse.ok(orgGraphService.buildGraph(rootId, depth), TraceIdUtil.get());
    }

    /** 某节点的邻居（入边/出边 + 边类型），用于图谱遍历。 */
    @GetMapping("/graph/neighbors")
    @RequiresPerm("org.structure:view")
    public ApiResponse<Map<String, Object>> neighbors(
            @RequestParam String nodeType, @RequestParam Long bizId) {
        return ApiResponse.ok(orgGraphService.neighbors(nodeType, bizId), TraceIdUtil.get());
    }

    /** 面包屑：母公司 → 当前公司。 */
    @GetMapping("/companies/{id}/path")
    @RequiresPerm("org.company:view")
    public ApiResponse<List<Company>> companyPath(@PathVariable Long id) {
        assertCompany(id);
        return ApiResponse.ok(orgGraphService.ancestors(id), TraceIdUtil.get());
    }

    /** 子树公司 ID（含自身），供数据范围与级联筛选使用。 */
    @GetMapping("/companies/{id}/descendants")
    @RequiresPerm("org.company:view")
    public ApiResponse<Set<Long>> descendants(@PathVariable Long id) {
        assertCompany(id);
        return ApiResponse.ok(orgService.descendantIds(id), TraceIdUtil.get());
    }

    // ---- 公司 ----

    @GetMapping("/companies")
    @RequiresPerm("org.company:view")
    public ApiResponse<List<Company>> companies() {
        return ApiResponse.ok(orgService.listCompanies(), TraceIdUtil.get());
    }

    @GetMapping("/companies/{id}")
    @RequiresPerm("org.company:view")
    public ApiResponse<Company> company(@PathVariable Long id) {
        assertCompany(id);
        return ApiResponse.ok(orgService.getCompany(id), TraceIdUtil.get());
    }

    @PostMapping("/companies")
    @RequiresPerm("org.company:create")
    @Audited(module = "org", action = "create_company")
    public ApiResponse<Company> createCompany(@RequestBody Company company) {
        // 新公司没有已存记录，parentId 是唯一的归属来源；为 null（新建根公司）时受限账号被拒
        rbacService.assertCompanyAccess(SecurityUtils.current(), company.getParentId());
        return ApiResponse.ok(orgService.createCompany(company), TraceIdUtil.get());
    }

    /**
     * 编辑公司。
     *
     * <p>仅当请求体显式携带 {@code parentId} 时才变更上级公司，避免「只改名字/状态」
     * 这类局部更新被误判为「变更为母公司」而把子公司摘挂成根节点。
     */
    @PutMapping("/companies/{id}")
    @RequiresPerm("org.company:update")
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
        assertCompany(id);
        // 变更上级会改变归属，新上级同样必须在范围内
        if (body.containsKey("parentId")) {
            rbacService.assertCompanyAccess(SecurityUtils.current(), company.getParentId());
        }
        return ApiResponse.ok(
                orgService.updateCompany(id, company, body.containsKey("parentId")),
                TraceIdUtil.get());
    }

    /** 变更上级公司（带防环校验）。 */
    @PutMapping("/companies/{id}/parent")
    @RequiresPerm("org.company:update")
    @Audited(module = "org", action = "change_company_parent")
    public ApiResponse<Company> changeParent(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long parentId = body.get("parentId") == null
                ? null
                : Long.valueOf(body.get("parentId").toString());
        assertCompany(id);
        // 挂到范围外的母公司下等于把整棵子树搬出可见范围，必须同时校验新上级
        rbacService.assertCompanyAccess(SecurityUtils.current(), parentId);
        return ApiResponse.ok(orgService.changeParent(id, parentId), TraceIdUtil.get());
    }

    /** 启用/停用公司（图谱节点快捷操作，不触发上级变更）。 */
    @PutMapping("/companies/{id}/status")
    @RequiresPerm("org.company:update")
    @Audited(module = "org", action = "update_company_status")
    public ApiResponse<Company> updateCompanyStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        assertCompany(id);
        return ApiResponse.ok(
                orgService.updateCompanyStatus(id, intOf(body.get("status"))), TraceIdUtil.get());
    }

    @DeleteMapping("/companies/{id}")
    @RequiresPerm("org.company:delete")
    @Audited(module = "org", action = "delete_company")
    public ApiResponse<Void> deleteCompany(@PathVariable Long id) {
        assertCompany(id);
        orgService.deleteCompany(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    // ---- 部门 ----

    @GetMapping("/departments")
    @RequiresPerm("org.department:view")
    public ApiResponse<List<Department>> departments(@RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(orgService.listDepartments(companyId), TraceIdUtil.get());
    }

    @GetMapping("/departments/{id}")
    @RequiresPerm("org.department:view")
    public ApiResponse<Department> department(@PathVariable Long id) {
        assertDepartment(id);
        return ApiResponse.ok(orgService.getDepartment(id), TraceIdUtil.get());
    }

    @PostMapping("/departments")
    @RequiresPerm("org.department:create")
    @Audited(module = "org", action = "create_department")
    public ApiResponse<Department> createDepartment(@RequestBody Department department) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), department.getCompanyId());
        return ApiResponse.ok(orgService.createDepartment(department), TraceIdUtil.get());
    }

    @PutMapping("/departments/{id}")
    @RequiresPerm("org.department:update")
    @Audited(module = "org", action = "update_department")
    public ApiResponse<Department> updateDepartment(
            @PathVariable Long id, @RequestBody Department department) {
        assertDepartment(id);
        // 改归属部门等同于把数据搬到另一个公司，请求体里的公司号也要校验
        rbacService.assertCompanyAccess(SecurityUtils.current(), department.getCompanyId());
        return ApiResponse.ok(orgService.updateDepartment(id, department), TraceIdUtil.get());
    }

    /** 启用/停用部门（图谱节点快捷操作）。 */
    @PutMapping("/departments/{id}/status")
    @RequiresPerm("org.department:update")
    @Audited(module = "org", action = "update_department_status")
    public ApiResponse<Department> updateDepartmentStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        assertDepartment(id);
        return ApiResponse.ok(
                orgService.updateDepartmentStatus(id, intOf(body.get("status"))),
                TraceIdUtil.get());
    }

    @DeleteMapping("/departments/{id}")
    @RequiresPerm("org.department:delete")
    @Audited(module = "org", action = "delete_department")
    public ApiResponse<Void> deleteDepartment(@PathVariable Long id) {
        assertDepartment(id);
        orgService.deleteDepartment(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    /**
     * 对象级数据范围断言。
     *
     * <p>以库中存储的归属为准，不采信请求体 —— 否则把请求体写成范围内公司就能读到范围外数据。
     */
    private void assertCompany(Long id) {
        // getCompany 负责「不存在 → 404」；公司的主键本身就是它的归属标识
        orgService.getCompany(id);
        rbacService.assertCompanyAccess(SecurityUtils.current(), id);
    }

    /**
     * 部门归属断言。
     *
     * <p>部门自带 {@code companyId}，直接用它判定；找不到部门时由 getDepartment 抛 404，
     * 不会因为「查不到 = 放行」而漏判。
     */
    private void assertDepartment(Long id) {
        rbacService.assertCompanyAccess(
                SecurityUtils.current(), orgService.getDepartment(id).getCompanyId());
    }

    private Integer intOf(Object raw) {
        return raw == null ? null : Integer.valueOf(raw.toString());
    }

    private Long longOf(Object raw) {
        return raw == null ? null : Long.valueOf(raw.toString());
    }
}
