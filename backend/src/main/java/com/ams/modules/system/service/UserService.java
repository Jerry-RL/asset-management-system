package com.ams.modules.system.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.common.web.PageResult;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.system.entity.Role;
import com.ams.modules.system.entity.UserRole;
import com.ams.modules.system.mapper.RoleMapper;
import com.ams.modules.system.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户管理（FR-OP-001）：后台操作人员账号、角色分配、密码。
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserMapper userMapper,
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public PageResult<User> page(long page, long pageSize, String keyword, Long companyId) {
        Page<User> result = userMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<User>()
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(User::getUsername, keyword)
                                        .or().like(User::getName, keyword))
                        .eq(companyId != null, User::getCompanyId, companyId)
                        .orderByDesc(User::getId));
        // 不返回密码哈希
        result.getRecords().forEach(u -> u.setPasswordHash(null));
        return PageResult.of(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public User get(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        user.setPasswordHash(null);
        return user;
    }

    public User create(User user, List<Long> roleIds) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, user.getUsername())) > 0) {
            throw new AppException(ErrorCode.CONFLICT, "用户名已存在");
        }
        user.setId(null);
        user.setPasswordHash(passwordEncoder.encode(
                user.getPasswordHash() == null ? "123456" : user.getPasswordHash()));
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
