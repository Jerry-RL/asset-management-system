package com.ams.modules.system.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.org.entity.User;
import com.ams.modules.system.service.UserService;
import com.ams.platform.security.Audited;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.RequiresPerm;
import com.ams.platform.security.SecurityUtils;
import java.util.List;
import java.util.Map;
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
 * 用户管理接口。
 */
@RestController
@RequestMapping("/api/v1/system/users")
public class UserController {

    private final UserService userService;
    private final RbacService rbacService;

    public UserController(UserService userService, RbacService rbacService) {
        this.userService = userService;
        this.rbacService = rbacService;
    }

    /**
     * 人员列表。
     *
     * <p>权限按用途分流，因为这是同一个端点承担两种页面：
     * <ul>
     *   <li>人员维护（无 {@code roleId}）→ 需要 {@code org.user:view}（注解强制）；</li>
     *   <li>角色成员（带 {@code roleId}）→ 额外要求 {@code system.role:view}。
     *       这里比设计 4.5 的表格更严：成员列表会返回姓名 / 手机号等个人信息，
     *       只有角色管理权、没有人员读权限的账号不应看到。</li>
     * </ul>
     */
    @GetMapping
    @RequiresPerm("org.user:view")
    public ApiResponse<PageResult<User>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long roleId) {
        if (roleId != null) {
            rbacService.assertPermission(SecurityUtils.current(), "system.role", "view");
        }
        return ApiResponse.ok(
                userService.page(page, pageSize, keyword, companyId, departmentId, roleId),
                TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    @RequiresPerm("org.user:view")
    public ApiResponse<User> get(@PathVariable Long id) {
        User user = userService.get(id);
        rbacService.assertCompanyAccess(SecurityUtils.current(), user.getCompanyId());
        return ApiResponse.ok(user, TraceIdUtil.get());
    }

    @PostMapping
    @RequiresPerm("org.user:create")
    @Audited(module = "org", action = "user_create")
    public ApiResponse<User> create(@RequestBody Map<String, Object> body) {
        User user = new User();
        user.setUsername((String) body.get("username"));
        user.setPasswordHash((String) body.get("password"));
        user.setName((String) body.get("name"));
        user.setPhone((String) body.get("phone"));
        user.setCompanyId(body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString()));
        user.setDepartmentId(body.get("departmentId") == null ? null : Long.valueOf(body.get("departmentId").toString()));
        user.setStatus(body.get("status") == null ? 1 : Integer.valueOf(body.get("status").toString()));
        List<Long> roleIds = parseRoleIds(body.get("roleIds"));
        // 新建没有已存记录，请求体是唯一的归属来源，必须落库前校验
        rbacService.assertCompanyAccess(SecurityUtils.current(), user.getCompanyId());
        return ApiResponse.ok(userService.create(user, roleIds), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    @RequiresPerm("org.user:update")
    @Audited(module = "org", action = "user_update")
    public ApiResponse<User> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User user = new User();
        user.setName((String) body.get("name"));
        user.setPhone((String) body.get("phone"));
        user.setCompanyId(body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString()));
        user.setDepartmentId(body.get("departmentId") == null ? null : Long.valueOf(body.get("departmentId").toString()));
        user.setStatus(body.get("status") == null ? null : Integer.valueOf(body.get("status").toString()));
        List<Long> roleIds = parseRoleIds(body.get("roleIds"));
        // 更新：以库中存储的归属为准，不能只看请求体（否则可以把自己范围外的账号改到范围内再读）
        User stored = userService.get(id);
        rbacService.assertCompanyAccess(SecurityUtils.current(), stored.getCompanyId());
        rbacService.assertCompanyAccess(SecurityUtils.current(), user.getCompanyId());
        return ApiResponse.ok(userService.update(id, user, roleIds), TraceIdUtil.get());
    }

    /** 启用/停用账号（图谱节点快捷操作）。 */
    @PutMapping("/{id}/status")
    @RequiresPerm("org.user:update")
    @Audited(module = "org", action = "user_update_status")
    public ApiResponse<User> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer status = body.get("status") == null ? null : Integer.valueOf(body.get("status").toString());
        rbacService.assertCompanyAccess(SecurityUtils.current(), userService.get(id).getCompanyId());
        return ApiResponse.ok(userService.updateStatus(id, status), TraceIdUtil.get());
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("org.user:delete")
    @Audited(module = "org", action = "user_delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), userService.get(id).getCompanyId());
        userService.delete(id);
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/{id}/reset-password")
    @RequiresPerm("org.user:update")
    @Audited(module = "org", action = "user_reset_password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        rbacService.assertCompanyAccess(SecurityUtils.current(), userService.get(id).getCompanyId());
        userService.resetPassword(id, body.get("newPassword"));
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @SuppressWarnings("unchecked")
    private List<Long> parseRoleIds(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(o -> Long.valueOf(o.toString())).toList();
        }
        return null;
    }
}
