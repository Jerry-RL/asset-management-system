package com.ams.modules.system.controller;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.common.web.TraceIdUtil;
import com.ams.modules.org.entity.User;
import com.ams.modules.system.service.UserService;
import java.util.List;
import java.util.Map;
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

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<PageResult<User>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long companyId) {
        return ApiResponse.ok(userService.page(page, pageSize, keyword, companyId), TraceIdUtil.get());
    }

    @GetMapping("/{id}")
    public ApiResponse<User> get(@PathVariable Long id) {
        return ApiResponse.ok(userService.get(id), TraceIdUtil.get());
    }

    @PostMapping
    public ApiResponse<User> create(@RequestBody Map<String, Object> body) {
        User user = new User();
        user.setUsername((String) body.get("username"));
        user.setPasswordHash((String) body.get("password"));
        user.setName((String) body.get("name"));
        user.setPhone((String) body.get("phone"));
        user.setCompanyId(body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString()));
        user.setDepartmentId(body.get("departmentId") == null ? null : Long.valueOf(body.get("departmentId").toString()));
        List<Long> roleIds = parseRoleIds(body.get("roleIds"));
        return ApiResponse.ok(userService.create(user, roleIds), TraceIdUtil.get());
    }

    @PutMapping("/{id}")
    public ApiResponse<User> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User user = new User();
        user.setName((String) body.get("name"));
        user.setPhone((String) body.get("phone"));
        user.setCompanyId(body.get("companyId") == null ? null : Long.valueOf(body.get("companyId").toString()));
        user.setDepartmentId(body.get("departmentId") == null ? null : Long.valueOf(body.get("departmentId").toString()));
        List<Long> roleIds = parseRoleIds(body.get("roleIds"));
        return ApiResponse.ok(userService.update(id, user, roleIds), TraceIdUtil.get());
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
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
