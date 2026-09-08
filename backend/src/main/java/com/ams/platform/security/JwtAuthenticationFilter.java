package com.ams.platform.security;

import com.ams.modules.org.entity.User;
import com.ams.modules.org.mapper.UserMapper;
import com.ams.platform.auth.JwtService;
import com.ams.platform.auth.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：解析 Bearer Token → 装载用户 → 写入 SecurityContext。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final RbacService rbacService;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserMapper userMapper,
            RbacService rbacService,
            TokenBlacklistService tokenBlacklistService) {
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.rbacService = rbacService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Claims claims = jwtService.parseAccessToken(token);
                if (tokenBlacklistService.isBlacklisted(claims.getId())) {
                    SecurityContextHolder.clearContext();
                } else {
                    Long userId = Long.valueOf(claims.getSubject());
                    User user = userMapper.selectById(userId);
                    if (user != null && user.getStatus() != null && user.getStatus() == 1) {
                        String clientType = claims.get("clientType", String.class);
                        LoginUser loginUser = rbacService.buildLoginUser(user, clientType);
                        var authentication = new UsernamePasswordAuthenticationToken(
                                loginUser, null, List.of());
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
