package com.ams.platform.security;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 认证上下文工具。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long currentUserIdOrNull() {
        LoginUser user = current();
        return user == null ? null : user.getUserId();
    }

    public static Long currentUserId() {
        LoginUser user = current();
        if (user == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return user.getUserId();
    }

    public static LoginUser current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }
}
