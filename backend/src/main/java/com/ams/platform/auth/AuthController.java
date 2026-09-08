package com.ams.platform.auth;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.TraceIdUtil;
import com.ams.platform.auth.dto.LoginRequest;
import com.ams.platform.auth.dto.LoginResponse;
import com.ams.platform.auth.dto.WechatBindRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（docs/api/README.md §2）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/captcha")
    public ApiResponse<Map<String, String>> captcha() {
        return ApiResponse.ok(authService.captcha(), TraceIdUtil.get());
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest req,
            @RequestHeader(value = "X-Client-Type", defaultValue = "admin") String clientType,
            HttpServletRequest request) {
        return ApiResponse.ok(authService.login(req, clientType, request), TraceIdUtil.get());
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        return ApiResponse.ok(authService.refresh(body.get("refreshToken"), request), TraceIdUtil.get());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7));
        }
        return ApiResponse.ok(null, TraceIdUtil.get());
    }

    @PostMapping("/wechat/login")
    public ApiResponse<LoginResponse> wechatLogin(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Client-Type", defaultValue = "tenant-mp") String clientType,
            HttpServletRequest request) {
        return ApiResponse.ok(authService.wechatLogin(body.get("code"), clientType, request),
                TraceIdUtil.get());
    }

    @PostMapping("/wechat/bind")
    public ApiResponse<LoginResponse> wechatBind(
            @Valid @RequestBody WechatBindRequest body,
            @RequestHeader(value = "X-Client-Type", defaultValue = "tenant-mp") String clientType,
            HttpServletRequest request) {
        return ApiResponse.ok(authService.wechatBind(body, clientType, request), TraceIdUtil.get());
    }
}
