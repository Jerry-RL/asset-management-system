package com.ams.modules.org.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.platform.security.CompanyScope;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
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
    private final RbacService rbacService;

    public OrgService(
            CompanyMapper companyMapper,
            DepartmentMapper departmentMapper,
            UserMapper userMapper,
            CompanyTreeService companyTreeService,
            RbacService rbacService) {
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.companyTreeService = companyTreeService;
        this.rbacService = rbacService;
    }

    // ---- 公司 ----

    /**
     * 公司列表（含上级公司名称，便于列表直接展示）。
     *
     * <p>按当前数据范围收敛：全局公司切换后只返回可访问公司子树，
     * 避免低权限账号通过公司管理/下拉选项看到完整集团架构。
     */
    public List<Company> listCompanies() {
        List<Company> companies = companyTreeService.listCompanies();
        Map<Long, String> nameById = companies.stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
        companies.forEach(c -> {
            if (c.getParentId() != null) {
                c.setParentName(nameById.get(c.getParentId()));
            }
        });
        CompanyScope scope = rbacService.companyScope(SecurityUtils.current());
        if (!scope.hasFilter()) {
            return companies; // 不受限
        }
        return companies.stream().filter(c -> scope.allows(c.getId())).toList();
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

    /**
     * 编辑公司。
     *
     * @param changeParent 调用方是否显式指定了上级公司；为 false 时保持原上级不变，
     *                     避免局部更新（改名字/停用）被误判为「变更为母公司」
     */
    @Transactional
    public Company updateCompany(Long id, Company company, boolean changeParent) {
        getCompany(id);
        if (!StringUtils.hasText(company.getName())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "公司名称不能为空");
        }
        // 上级变更走防环校验，避免通过普通编辑制造环
        if (changeParent) {
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

    /**
     * 启用/停用公司。
     *
     * <p>走独立端点，避免复用 {@link #updateCompany} 时因未传上级而误触发 {@code changeParent}，
     * 导致子公司被摘挂成根节点。
     */
    @Transactional
    public Company updateCompanyStatus(Long id, Integer status) {
        Company company = getCompany(id);
        company.setStatus(status == null ? 1 : status);
        companyMapper.updateById(company);
        return companyMapper.selectById(id);
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

    /** 部门列表（含所属公司名称、上级部门名称，便于列表直接展示）；按当前数据范围收敛。 */
    public List<Department> listDepartments(Long companyId) {
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<Department>()
                .eq(companyId != null, Department::getCompanyId, companyId)
                .orderByAsc(Department::getSort)
                .orderByAsc(Department::getId);
        // 全局公司切换：部门按所属公司收敛
        rbacService.applyCompanyScope(wrapper, SecurityUtils.current(), Department::getCompanyId);
        List<Department> departments = departmentMapper.selectList(wrapper);
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

    /** 启用/停用部门。 */
    @Transactional
    public Department updateDepartmentStatus(Long id, Integer status) {
        Department department = getDepartment(id);
        department.setStatus(status == null ? 1 : status);
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
