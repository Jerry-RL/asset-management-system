package com.ams.config;

import java.util.List;

/**
 * 免认证路径白名单（NFR-SEC-002）。
 *
 * <p>单独抽出成常量的原因：白名单有<strong>两个</strong>消费方，重复写两份必然漂移 ——
 * <ul>
 *   <li>{@code SecurityConfig}：这些路径不要求登录；</li>
 *   <li>{@code PermissionInterceptor}：{@code strict-perm=true} 时，未加
 *       {@code @RequiresPerm} 的变更类接口要按「非白名单」才拒绝 —— 否则
 *       {@code POST /auth/login} 这类接口会被一并拒掉，直接把系统锁死。</li>
 * </ul>
 */
public final class SecurityWhitelist {

    private SecurityWhitelist() {
    }

    public static final List<String> PATTERNS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/captcha",
            "/api/v1/auth/refresh",
            "/api/v1/auth/wechat/login",
            "/api/v1/auth/wechat/bind",
            "/api/v1/callbacks/**",
            "/api/v1/health/**",
            "/api/v1/public/**",
            // 附件「公开对象」读取：<img> 无法携带 Authorization，
            // 安全性由对象键内嵌的随机 UUID（不可枚举）保证
            "/api/v1/files/object/**",
            "/actuator/health",
            "/actuator/info",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api-docs/**",
            "/v3/api-docs/**");
}
