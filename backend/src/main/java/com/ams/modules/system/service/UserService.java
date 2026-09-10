package com.ams.modules.system.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.entity.Department;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.mapper.DepartmentMapper;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.UserRole;
import com.ams.modules.system.mapper.RoleMapper;
import com.ams.modules.system.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import com.ams.platform.security.SecurityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户管理（FR-OP-001）：后台操作人员账号、角色分配、密码。
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final CompanyMapper companyMapper;
    private final DepartmentMapper departmentMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserMapper userMapper,
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            CompanyMapper companyMapper,
            DepartmentMapper departmentMapper,
            PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public PageResult<User> page(long page, long pageSize, String keyword, Long companyId,
            Long departmentId) {
        Page<User> result = userMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<User>()
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(User::getUsername, keyword)
                                        .or().like(User::getName, keyword)
                                        .or().like(User::getPhone, keyword))
                        .eq(companyId != null, User::getCompanyId, companyId)
                        .eq(departmentId != null, User::getDepartmentId, departmentId)
                        .orderByDesc(User::getId));
        // 不返回密码哈希
        result.getRecords().forEach(u -> u.setPasswordHash(null));
        enrich(result.getRecords());
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public User get(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        user.setPasswordHash(null);
        enrich(List.of(user));
        return user;
    }

    /**
     * 补齐人员维护列表/表单所需的展示字段：所属公司名、所属部门名、角色（ID + 名称）。
     *
     * <p>批量按 ID 查字典/角色表后在内存 join，避免逐行查询造成 N+1。
     */
    private void enrich(List<User> users) {
        if (users == null || users.isEmpty()) {
            return;
        }
        Map<Long, String> companyNames = companyMapper.selectList(null).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName, (a, b) -> a));
        Map<Long, String> deptNames = departmentMapper.selectList(null).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
        Map<Long, String> roleNames = roleMapper.selectList(null).stream()
                .collect(Collectors.toMap(Role::getId, Role::getName, (a, b) -> a));

        List<Long> userIds = users.stream().map(User::getId).filter(Objects::nonNull).toList();
        Map<Long, List<Long>> roleIdsByUser = userIds.isEmpty()
                ? Map.of()
                : userRoleMapper.selectList(
                                new LambdaQueryWrapper<UserRole>().in(UserRole::getUserId, userIds))
                        .stream()
                        .collect(Collectors.groupingBy(UserRole::getUserId,
                                Collectors.mapping(UserRole::getRoleId, Collectors.toList())));

        for (User u : users) {
            u.setCompanyName(companyNames.get(u.getCompanyId()));
            u.setDepartmentName(deptNames.get(u.getDepartmentId()));
            List<Long> roleIds = roleIdsByUser.getOrDefault(u.getId(), List.of());
            u.setRoleIds(roleIds);
            u.setRoleNames(roleIds.stream()
                    .map(roleNames::get)
                    .filter(Objects::nonNull)
                    .toList());
        }
    }

    public User create(User user, List<Long> roleIds) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, user.getUsername())) > 0) {
            throw new AppException(ErrorCode.CONFLICT, "用户名已存在");
        }
        user.setId(null);
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        // 未填写初始密码时给默认密码，避免空密码账号
        user.setPasswordHash(passwordEncoder.encode(
                StringUtils.hasText(user.getPasswordHash()) ? user.getPasswordHash() : "123456"));
        userMapper.insert(user);
        bindRoles(user.getId(), roleIds);
        return get(user.getId());
    }

    public User update(Long id, User user, List<Long> roleIds) {
        user.setId(id);
        user.setPasswordHash(null); // 不允许直接改密码
        userMapper.updateById(user);
        if (roleIds != null) {
            bindRoles(id, roleIds);
        }
        return get(id);
    }

    /** 启用/停用账号（图谱节点快捷操作）。 */
    public User updateStatus(Long id, Integer status) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        user.setStatus(status == null ? 1 : status);
        userMapper.updateById(user);
        return get(id);
    }

    /** 删除账号（解除角色绑定后物理删除）。 */
    @Transactional
    public void delete(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        if (id.equals(SecurityUtils.currentUserIdOrNull())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "不能删除当前登录账号");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, id));
        userMapper.deleteById(id);
    }

    public void resetPassword(Long id, String newPassword) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    public Set<String> rolesOf(Long userId) {
        List<UserRole> urs = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        List<Long> roleIds = urs.stream().map(UserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(Role::getCode)
                .collect(Collectors.toSet());
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                UserRole ur = new UserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
    }
}
