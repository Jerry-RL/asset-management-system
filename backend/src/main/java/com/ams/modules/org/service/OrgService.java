package com.ams.modules.org.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 组织服务：公司、部门的维护，含图谱一致性守卫（防环、删除守卫）。
 */
@Service
public class OrgService {

    private final CompanyMapper companyMapper;
    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final CompanyTreeService companyTreeService;

    public OrgService(
            CompanyMapper companyMapper,
            DepartmentMapper departmentMapper,
            UserMapper userMapper,
            CompanyTreeService companyTreeService) {
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.companyTreeService = companyTreeService;
    }

    // ---- 公司 ----

    /** 公司列表（含上级公司名称，便于列表直接展示）。 */
    public List<Company> listCompanies() {
        List<Company> companies = companyTreeService.listCompanies();
        Map<Long, String> nameById = companies.stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
        companies.forEach(c -> {
            if (c.getParentId() != null) {
                c.setParentName(nameById.get(c.getParentId()));
            }
        });
        return companies;
    }

    public Company getCompany(Long id) {
        Company company = companyMapper.selectById(id);
        if (company == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "公司不存在");
        }
        return company;
    }

    @Transactional
    public Company createCompany(Company company) {
        if (!StringUtils.hasText(company.getName())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "公司名称不能为空");
        }
        company.setId(null);
        if (company.getStatus() == null) {
            company.setStatus(1);
        }
        if (company.getSort() == null) {
            company.setSort(0);
        }
        if (company.getParentId() != null) {
            getCompany(company.getParentId());
        }
        companyMapper.insert(company);
        return companyMapper.selectById(company.getId());
    }

    @Transactional
    public Company updateCompany(Long id, Company company) {
        Company existing = getCompany(id);
        if (!StringUtils.hasText(company.getName())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "公司名称不能为空");
        }
        // 上级变更走防环校验，避免通过普通编辑制造环
        if (company.getParentId() != null || existing.getParentId() != null) {
            companyTreeService.changeParent(id, company.getParentId());
            company.setParentId(null); // 已单独更新，避免重复写入
        }
        company.setId(id);
        companyMapper.updateById(company);
        return companyMapper.selectById(id);
    }

    @Transactional
    public void deleteCompany(Long id) {
        getCompany(id);
        if (companyTreeService.hasChildren(id)) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "存在下级公司，请先处理下级公司");
        }
        Long deptCount = departmentMapper.selectCount(new LambdaQueryWrapper<Department>()
                .eq(Department::getCompanyId, id));
        if (deptCount != null && deptCount > 0) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "公司下存在部门，请先处理部门");
        }
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getCompanyId, id));
        if (userCount != null && userCount > 0) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "公司下存在员工，请先调整员工归属");
        }
        companyMapper.deleteById(id);
    }

    /** 变更上级公司（独立入口，带防环校验）。 */
    @Transactional
    public Company changeParent(Long id, Long parentId) {
        return companyTreeService.changeParent(id, parentId);
    }

    /** 面包屑：母公司 → 当前公司。 */
    public List<Company> companyPath(Long id) {
        getCompany(id);
        return companyTreeService.ancestorIds(id).stream().map(companyMapper::selectById).toList();
    }

    /** 子树公司 ID（含自身）。 */
    public Set<Long> descendantIds(Long id) {
        getCompany(id);
        return companyTreeService.descendantIds(id);
    }

    // ---- 部门 ----

    /** 部门列表（含所属公司名称、上级部门名称，便于列表直接展示）。 */
    public List<Department> listDepartments(Long companyId) {
        List<Department> departments = departmentMapper.selectList(new LambdaQueryWrapper<Department>()
                .eq(companyId != null, Department::getCompanyId, companyId)
                .orderByAsc(Department::getSort)
                .orderByAsc(Department::getId));
        if (departments.isEmpty()) {
            return departments;
        }
        Map<Long, String> companyNames = companyTreeService.listCompanies().stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
        List<Department> all = departmentMapper.selectList(null);
        Map<Long, String> deptNames = all.stream()
                .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
        departments.forEach(d -> {
            d.setCompanyName(companyNames.get(d.getCompanyId()));
            if (d.getParentId() != null) {
                d.setParentName(deptNames.get(d.getParentId()));
            }
        });
        return departments;
    }

    public Department getDepartment(Long id) {
        Department department = departmentMapper.selectById(id);
        if (department == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "部门不存在");
        }
        return department;
    }

    @Transactional
    public Department createDepartment(Department department) {
        if (department.getCompanyId() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "请先选择所属公司");
        }
        getCompany(department.getCompanyId());
        if (!StringUtils.hasText(department.getName())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "部门名称不能为空");
        }
        department.setId(null);
        if (department.getStatus() == null) {
            department.setStatus(1);
        }
        if (department.getSort() == null) {
            department.setSort(0);
        }
        if (department.getParentId() != null) {
            Department parent = getDepartment(department.getParentId());
            if (!parent.getCompanyId().equals(department.getCompanyId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "上级部门与当前公司不一致");
            }
        }
        departmentMapper.insert(department);
        return departmentMapper.selectById(department.getId());
    }

    @Transactional
    public Department updateDepartment(Long id, Department department) {
        Department existing = getDepartment(id);
        if (!StringUtils.hasText(department.getName())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "部门名称不能为空");
        }
        Long companyId = department.getCompanyId() == null
                ? existing.getCompanyId()
                : department.getCompanyId();
        getCompany(companyId);
        if (department.getParentId() != null) {
            if (department.getParentId().equals(id)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "不能将部门挂在自身之下");
            }
            Department parent = getDepartment(department.getParentId());
            if (!parent.getCompanyId().equals(companyId)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "上级部门与当前公司不一致");
            }
        }
        department.setId(id);
        department.setCompanyId(companyId);
        departmentMapper.updateById(department);
        return departmentMapper.selectById(id);
    }

    @Transactional
    public void deleteDepartment(Long id) {
        getDepartment(id);
        Long childCount = departmentMapper.selectCount(new LambdaQueryWrapper<Department>()
                .eq(Department::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "存在下级部门，请先处理下级部门");
        }
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getDepartmentId, id));
        if (userCount != null && userCount > 0) {
            throw new AppException(ErrorCode.BUSINESS_ERROR, "部门下存在员工，请先调整员工归属");
        }
        departmentMapper.deleteById(id);
    }
}
