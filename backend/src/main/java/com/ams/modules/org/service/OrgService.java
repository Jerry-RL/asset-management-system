package com.ams.modules.org.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 组织服务：公司、部门（FR-ORG-001）。
 */
@Service
public class OrgService {

    private final CompanyMapper companyMapper;
    private final DepartmentMapper departmentMapper;

    public OrgService(CompanyMapper companyMapper, DepartmentMapper departmentMapper) {
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
    }

    public List<Company> listCompanies() {
        return companyMapper.selectList(
                new LambdaQueryWrapper<Company>().orderByAsc(Company::getId));
    }

    public Company getCompany(Long id) {
        Company company = companyMapper.selectById(id);
        if (company == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return company;
    }

    public Company createCompany(Company company) {
        companyMapper.insert(company);
        return company;
    }

    public Company updateCompany(Long id, Company company) {
        company.setId(id);
        companyMapper.updateById(company);
        return getCompany(id);
    }

    public List<Department> listDepartments(Long companyId) {
        return departmentMapper.selectList(
                new LambdaQueryWrapper<Department>()
                        .eq(companyId != null, Department::getCompanyId, companyId)
                        .orderByAsc(Department::getId));
    }

    public Department createDepartment(Department department) {
        departmentMapper.insert(department);
        return department;
    }

    public Department updateDepartment(Long id, Department department) {
        department.setId(id);
        departmentMapper.updateById(department);
        return departmentMapper.selectById(id);
    }
}
