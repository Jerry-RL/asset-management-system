package com.ams.modules.org.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.service.OrgService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织接口：公司、部门。
 */
@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping("/companies")
    public ApiResponse<List<Company>> companies() {
        return ApiResponse.ok(orgService.listCompanies(), TraceIdUtil.get());
    }

    @GetMapping("/companies/{id}")
    public ApiResponse<Company> company(@PathVariable Long id) {
        return ApiResponse.ok(orgService.getCompany(id), TraceIdUtil.get());
    }

    @PostMapping("/companies")
    public ApiResponse<Company> createCompany(@RequestBody Company company) {
        return ApiResponse.ok(orgService.createCompany(company), TraceIdUtil.get());
    }

    @PutMapping("/companies/{id}")
    public ApiResponse<Company> updateCompany(@PathVariable Long id, @RequestBody Company company) {
        return ApiResponse.ok(orgService.updateCompany(id, company), TraceIdUtil.get());
    }

    @GetMapping("/departments")
    public ApiResponse<List<Department>> departments(@RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(orgService.listDepartments(companyId), TraceIdUtil.get());
    }

    @PostMapping("/departments")
    public ApiResponse<Department> createDepartment(@RequestBody Department department) {
        return ApiResponse.ok(orgService.createDepartment(department), TraceIdUtil.get());
    }

    @PutMapping("/departments/{id}")
    public ApiResponse<Department> updateDepartment(
            @PathVariable Long id, @RequestBody Department department) {
        return ApiResponse.ok(orgService.updateDepartment(id, department), TraceIdUtil.get());
    }
}
