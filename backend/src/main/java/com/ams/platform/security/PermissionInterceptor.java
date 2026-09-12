package com.ams.platform.security;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.config.SecurityWhitelist;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 操作级权限拦截器（设计 6.1）。
 *
 * <h2>判定顺序</h2>
 * <ol>
 *   <li>非 {@link HandlerMethod}（静态资源等）→ 放行；</li>
 *   <li>命中 {@link RequiresPerm} → 无登录主体 401；{@code super_admin} 放行；
 *       否则 {@code assertPermission} 不通过则 403；</li>
 *   <li>未命中注解 → 仅在 {@code strict-perm=true} 时，对<strong>非白名单的变更类请求</strong>
 *       （POST / PUT / PATCH / DELETE）拒绝。</li>
 * </ol>
 *
 * <p><strong>白名单必须排除在外</strong>：{@code POST /auth/login} 等接口没有注解，
 * 若一并拒绝会让任何人（包括管理员）都无法登录。
 *
 * <p>默认档 {@code strict-perm=false} 下，本拦截器只对已注解接口生效 —— 这就是
 * 「框架先行 + 核心模块接入」的过渡形态，未接入接口在矩阵上以
 * {@code enforced:false} 显式标识为已知缺口。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final RbacService rbacService;
    private final boolean strictPerm;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public PermissionInterceptor(
            RbacService rbacService,
            @Value("${ams.security.strict-perm:false}") boolean strictPerm) {
        this.rbacService = rbacService;
        this.strictPerm = strictPerm;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        String permission = resolvePermission(handlerMethod);
        if (permission == null) {
            // 过渡档：未接入的接口保持「仅登录校验」；严格档：变更类且不在白名单才拒绝
            if (strictPerm
                    && MUTATING_METHODS.contains(request.getMethod())
                    && !isWhitelisted(request.getRequestURI())) {
                throw new AppException(
                        ErrorCode.FORBIDDEN, "该接口尚未接入操作级权限校验：" + request.getRequestURI());
            }
            return true;
        }

        LoginUser user = SecurityUtils.current();
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (user.isSuperAdmin()) {
            return true;
        }
        int idx = permission.lastIndexOf(':');
        rbacService.assertPermission(
                user, permission.substring(0, idx), permission.substring(idx + 1));
        return true;
    }

    /** 方法级注解优先，其次类级（方法级覆盖类级）。 */
    private String resolvePermission(HandlerMethod handlerMethod) {
        RequiresPerm methodLevel =
                handlerMethod.getMethodAnnotation(RequiresPerm.class);
        if (methodLevel != null) {
            return methodLevel.value();
        }
        RequiresPerm typeLevel = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(), RequiresPerm.class);
        return typeLevel == null ? null : typeLevel.value();
    }

    private boolean isWhitelisted(String uri) {
        return SecurityWhitelist.PATTERNS.stream().anyMatch(p -> pathMatcher.match(p, uri));
    }
}
