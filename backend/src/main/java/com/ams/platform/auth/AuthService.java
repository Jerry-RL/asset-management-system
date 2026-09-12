package com.ams.platform.auth;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.lease.entity.Tenant;
import com.ams.modules.lease.mapper.TenantMapper;
import com.ams.modules.lease.service.TenantService;
import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.system.entity.LoginLog;
import com.ams.modules.system.mapper.LoginLogMapper;
import com.ams.platform.auth.dto.CompanyScopeOptions;
import com.ams.platform.auth.dto.LoginRequest;
import com.ams.platform.auth.dto.LoginResponse;
import com.ams.platform.auth.dto.WechatBindRequest;
import com.ams.platform.integration.wechat.WechatMiniProgramClient;
import com.ams.platform.security.CompanyScope;
import com.ams.platform.security.LoginUser;
import com.ams.platform.security.RbacService;
import com.ams.platform.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务：PC 登录、Token 刷新、登出、微信登录绑定（NFR-SEC-001/006 / FR-MPU-001/002）。
 */
@Service
public class AuthService {

    private static final int MAX_FAIL = 5;
    private static final long LOCK_SECONDS = 15 * 60;
    private static final String FAIL_PREFIX = "login:fail:";
    private static final String BIND_TICKET_PREFIX = "wechat:bind:";
    private static final long BIND_TICKET_TTL_MINUTES = 15;

    private final UserMapper userMapper;
    private final RbacService rbacService;
    private final JwtService jwtService;
    private final CaptchaService captchaService;
    private final PasswordEncoder passwordEncoder;
    private final LoginLogMapper loginLogMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TokenBlacklistService tokenBlacklistService;
    private final WechatMiniProgramClient wechatMiniProgramClient;
    private final TenantMapper tenantMapper;
    private final TenantService tenantService;
    private final CompanyTreeService companyTreeService;

    public AuthService(
            UserMapper userMapper,
            RbacService rbacService,
            JwtService jwtService,
            CaptchaService captchaService,
            PasswordEncoder passwordEncoder,
            LoginLogMapper loginLogMapper,
            RedisTemplate<String, Object> redisTemplate,
            TokenBlacklistService tokenBlacklistService,
            WechatMiniProgramClient wechatMiniProgramClient,
            TenantMapper tenantMapper,
            TenantService tenantService,
            CompanyTreeService companyTreeService) {
        this.userMapper = userMapper;
        this.rbacService = rbacService;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
        this.passwordEncoder = passwordEncoder;
        this.loginLogMapper = loginLogMapper;
        this.redisTemplate = redisTemplate;
        this.tokenBlacklistService = tokenBlacklistService;
        this.wechatMiniProgramClient = wechatMiniProgramClient;
        this.tenantMapper = tenantMapper;
        this.tenantService = tenantService;
        this.companyTreeService = companyTreeService;
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
     * 微信小程序登录：code → openid → 已绑定用户发 JWT；未绑定返回 needBind + bindTicket。
     */
    public LoginResponse wechatLogin(String code, String clientType, HttpServletRequest request) {
        WechatMiniProgramClient.SessionResult session = wechatMiniProgramClient.code2session(code);
        String openid = session.openid();

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getWechatOpenid, openid));
        if (user == null && clientType != null && clientType.startsWith("tenant")) {
            Tenant tenant = tenantMapper.selectOne(
                    new LambdaQueryWrapper<Tenant>().eq(Tenant::getWechatOpenid, openid));
            if (tenant != null) {
                user = userMapper.selectOne(
                        new LambdaQueryWrapper<User>().eq(User::getTenantId, tenant.getId()));
            }
        }
        if (user != null) {
            if (user.getStatus() == null || user.getStatus() != 1) {
                throw new AppException(ErrorCode.FORBIDDEN, "账户已停用");
            }
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(user);
            recordLoginLog(user, user.getUsername(), request, "success", "wechat");
            LoginUser loginUser = rbacService.buildLoginUser(user, clientType);
            return toResponse(user, loginUser, clientType);
        }

        String ticket = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(BIND_TICKET_PREFIX + ticket, openid,
                BIND_TICKET_TTL_MINUTES, TimeUnit.MINUTES);
        return LoginResponse.builder()
                .needBind(true)
                .bindTicket(ticket)
                .expiresIn(0)
                .build();
    }

    /**
     * 微信身份绑定（FR-MPU-002）：手机号实名 → 绑定 openid → 发 JWT。
     */
    @Transactional
    public LoginResponse wechatBind(WechatBindRequest req, String clientType, HttpServletRequest request) {
        String openid = resolveOpenidForBind(req);
        boolean tenantClient = clientType == null || clientType.startsWith("tenant");

        if (tenantClient) {
            return bindTenant(openid, req, clientType, request);
        }
        return bindWorker(openid, req, clientType, request);
    }

    private LoginResponse bindTenant(String openid, WechatBindRequest req, String clientType,
            HttpServletRequest request) {
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getWechatOpenid, openid));
        if (existing != null) {
            LoginUser loginUser = rbacService.buildLoginUser(existing, clientType);
            return toResponse(existing, loginUser, clientType);
        }

        Tenant tenant = tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getPhone, req.getPhone()));
        if (tenant == null) {
            Tenant created = new Tenant();
            created.setName(req.getName());
            created.setPhone(req.getPhone());
            created.setIdNo(req.getIdNo());
            created.setTenantType("person");
            created.setWechatOpenid(openid);
            created.setStatus(1);
            tenant = tenantService.create(created);
            // create() 返回脱敏对象，重新加载以写 openid
            tenant = tenantMapper.selectById(tenant.getId());
            tenant.setWechatOpenid(openid);
            tenantMapper.updateById(tenant);
        } else {
            if (tenant.getWechatOpenid() != null && !tenant.getWechatOpenid().isBlank()
                    && !tenant.getWechatOpenid().equals(openid)) {
                throw new AppException(ErrorCode.CONFLICT, "该手机号已绑定其他微信");
            }
            tenant.setWechatOpenid(openid);
            if (tenant.getName() == null || tenant.getName().isBlank()) {
                tenant.setName(req.getName());
            }
            tenantMapper.updateById(tenant);
        }

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getTenantId, tenant.getId()));
        if (user == null) {
            user = new User();
            user.setUsername("wx_t_" + tenant.getId());
            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setName(req.getName());
            user.setPhone(req.getPhone());
            user.setStatus(1);
            user.setTenantId(tenant.getId());
            user.setWechatOpenid(openid);
            userMapper.insert(user);
        } else {
            user.setWechatOpenid(openid);
            user.setName(req.getName());
            user.setPhone(req.getPhone());
            userMapper.updateById(user);
        }

        if (req.getBindTicket() != null) {
            redisTemplate.delete(BIND_TICKET_PREFIX + req.getBindTicket());
        }
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);
        recordLoginLog(user, user.getUsername(), request, "success", "wechat_bind");
        LoginUser loginUser = rbacService.buildLoginUser(user, clientType);
        return toResponse(user, loginUser, clientType);
    }

    private LoginResponse bindWorker(String openid, WechatBindRequest req, String clientType,
            HttpServletRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, req.getPhone()));
        if (user == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "未找到该手机号对应的员工账号，请联系管理员开通");
        }
        if (user.getWechatOpenid() != null && !user.getWechatOpenid().isBlank()
                && !user.getWechatOpenid().equals(openid)) {
            throw new AppException(ErrorCode.CONFLICT, "该账号已绑定其他微信");
        }
        user.setWechatOpenid(openid);
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);
        if (req.getBindTicket() != null) {
            redisTemplate.delete(BIND_TICKET_PREFIX + req.getBindTicket());
        }
        recordLoginLog(user, user.getUsername(), request, "success", "wechat_bind_worker");
        LoginUser loginUser = rbacService.buildLoginUser(user, clientType);
        return toResponse(user, loginUser, clientType);
    }

    private String resolveOpenidForBind(WechatBindRequest req) {
        if (req.getBindTicket() != null && !req.getBindTicket().isBlank()) {
            Object cached = redisTemplate.opsForValue().get(BIND_TICKET_PREFIX + req.getBindTicket());
            if (cached == null) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "绑定票据无效或已过期，请重新微信登录");
            }
            return cached.toString();
        }
        if (req.getCode() != null && !req.getCode().isBlank()) {
            return wechatMiniProgramClient.code2session(req.getCode()).openid();
        }
        throw new AppException(ErrorCode.BAD_REQUEST, "缺少 bindTicket 或 code");
    }

    private LoginResponse toResponse(User user, LoginUser loginUser, String clientType) {
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUsername(), clientType);
        String refreshToken = jwtService.createRefreshToken(user.getId(), user.getUsername(), clientType);
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(Duration.ofHours(2).toSeconds())
                .needBind(false)
                .user(LoginResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .name(user.getName())
                        .companyId(user.getCompanyId())
                        .tenantId(user.getTenantId())
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

    /**
     * 顶栏「全局公司切换」可选项（FR-NFR-SEC-002 数据范围）。
     *
     * <p>可切换范围 = 用户所属公司及其全部下级公司；super_admin / 数据范围 all 为全部启用公司。
     * 该范围与 {@code RbacService#companyScope} 同源，保证「能切到」与「能看见」一致。
     */
    public CompanyScopeOptions switchableCompanies() {
        LoginUser user = SecurityUtils.current();
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        boolean unrestricted = user.isSuperAdmin() || "all".equals(user.getDataScope());
        CompanyScope allowed = rbacService.switchableCompanyScope(user);
        // 按公司树顺序返回，前端缩进展示时层级才连贯（父在上、子在下）
        List<CompanyScopeOptions.Item> companies =
                companyTreeService.listCompaniesTreeOrdered().stream()
                        .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                        // 统一走 allows()：不受限账号此处为真，受限账号按已扣除排除子树的集合判定。
                        // 不再用 unrestricted 短路，否则被排除的公司会重新出现在切换器里，
                        // 出现「能切进去、但切进去什么都看不到」的不一致。
                        .filter(c -> allowed.allows(c.getId()))
                        .map(c -> CompanyScopeOptions.Item.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .shortName(c.getShortName())
                                .parentId(c.getParentId())
                                .build())
                        .toList();
        return CompanyScopeOptions.builder()
                .companies(companies)
                .homeCompanyId(user.getHomeCompanyId())
                .activeCompanyId(effectiveSelectedCompanyId(user, unrestricted))
                .unrestricted(unrestricted)
                .scoped(user.isCompanyScoped())
                .build();
    }

    /**
     * 前端下拉应选中的公司 ID。
     *
     * @return null 表示选中「全部公司」（仅不受限账号且未显式切换时出现）；
     *         其余情况返回生效公司，未切换时即所属公司
     */
    private Long effectiveSelectedCompanyId(LoginUser user, boolean unrestricted) {
        if (unrestricted && !user.isCompanyScoped()) {
            return null;
        }
        return user.getCompanyId();
    }
}
