package com.ams.platform.auth;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.system.entity.LoginLog;
import com.ams.modules.system.mapper.LoginLogMapper;
import com.ams.platform.auth.dto.LoginRequest;
import com.ams.platform.auth.dto.LoginResponse;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.RbacService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务：PC 登录、Token 刷新、登出、微信登录（NFR-SEC-001/006）。
 */
@Service
public class AuthService {

    private static final int MAX_FAIL = 5;
    private static final long LOCK_SECONDS = 15 * 60;
    private static final String FAIL_PREFIX = "login:fail:";

    private final UserMapper userMapper;
    private final RbacService rbacService;
    private final JwtService jwtService;
    private final CaptchaService captchaService;
    private final PasswordEncoder passwordEncoder;
    private final LoginLogMapper loginLogMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthService(
            UserMapper userMapper,
            RbacService rbacService,
            JwtService jwtService,
            CaptchaService captchaService,
            PasswordEncoder passwordEncoder,
            LoginLogMapper loginLogMapper,
            RedisTemplate<String, Object> redisTemplate,
            TokenBlacklistService tokenBlacklistService) {
        this.userMapper = userMapper;
        this.rbacService = rbacService;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
        this.passwordEncoder = passwordEncoder;
        this.loginLogMapper = loginLogMapper;
        this.redisTemplate = redisTemplate;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    public LoginResponse login(LoginRequest req, String clientType, HttpServletRequest request) {
        captchaService.verify(req.getCaptchaId(), req.getCaptchaCode());
        String failKey = FAIL_PREFIX + req.getUsername();
        Object fails = redisTemplate.opsForValue().get(failKey);
        if (fails != null && Integer.parseInt(fails.toString()) >= MAX_FAIL) {
            recordLoginLog(null, req.getUsername(), request, "failed", "登录失败次数过多，账户已锁定");
            throw new AppException(ErrorCode.FORBIDDEN, "登录失败次数过多，请 15 分钟后再试");
        }

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, LOCK_SECONDS, TimeUnit.SECONDS);
            recordLoginLog(user, req.getUsername(), request, "failed", "用户名或密码错误");
            throw new AppException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            recordLoginLog(user, req.getUsername(), request, "failed", "账户已停用");
            throw new AppException(ErrorCode.FORBIDDEN, "账户已停用");
        }

        redisTemplate.delete(failKey);
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);
        recordLoginLog(user, req.getUsername(), request, "success", null);

        LoginUser loginUser = rbacService.buildLoginUser(user, clientType);
        return toResponse(user, loginUser, clientType);
    }

    public LoginResponse refresh(String refreshToken, HttpServletRequest request) {
        Claims claims;
        try {
            claims = jwtService.parseRefreshToken(refreshToken);
        } catch (Exception e) {
            throw new AppException(ErrorCode.TOKEN_EXPIRED, "刷新令牌无效或已过期");
        }
        Long userId = Long.valueOf(claims.getSubject());
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        String clientType = claims.get("clientType", String.class);
        LoginUser loginUser = rbacService.buildLoginUser(user, clientType);
        return toResponse(user, loginUser, clientType);
    }

    public void logout(String accessToken) {
        try {
            Claims claims = jwtService.parseAccessToken(accessToken);
            long remainMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            tokenBlacklistService.revoke(claims.getId(), remainMs);
        } catch (Exception ignored) {
            // 已过期 token 无需吊销
        }
    }

    public boolean isBlacklisted(String jti) {
        return tokenBlacklistService.isBlacklisted(jti);
    }

    /**
     * 微信小程序登录（用户端/工作端）。生产对接微信 code2session；
     * 此处对未绑定 openid 的 code 返回「需身份绑定」语义，绑定后复用 JWT。
     */
    public LoginResponse wechatLogin(String code, String clientType, HttpServletRequest request) {
        if (code == null || code.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "缺少微信授权 code");
        }
        // 生产：调用 WechatAdapter.code2session(code) 得到 openid，再按 openid 绑定 user。
        // 无 openid 映射时抛 40100，引导身份绑定（FR-MPU-001/002）。
        throw new AppException(ErrorCode.UNAUTHORIZED, "微信身份未绑定，请先完成身份绑定");
    }

    private LoginResponse toResponse(User user, LoginUser loginUser, String clientType) {
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUsername(), clientType);
        String refreshToken = jwtService.createRefreshToken(user.getId(), user.getUsername(), clientType);
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(Duration.ofHours(2).toSeconds())
                .user(LoginResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .name(user.getName())
                        .companyId(user.getCompanyId())
                        .roles(loginUser.getRoles())
                        .build())
                .build();
    }

    private void recordLoginLog(User user, String username, HttpServletRequest request,
            String result, String failReason) {
        LoginLog log = new LoginLog();
        log.setUserId(user == null ? null : user.getId());
        log.setUsername(username);
        log.setIp(clientIp(request));
        log.setResult(result);
        log.setFailReason(failReason);
        log.setCreatedAt(LocalDateTime.now());
        loginLogMapper.insert(log);
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public Map<String, String> captcha() {
        return captchaService.generate();
    }
}
