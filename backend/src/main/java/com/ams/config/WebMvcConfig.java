package com.ams.config;

import com.ams.platform.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册操作级权限拦截器（设计 6.1）。
 *
 * <p>拦截全部 {@code /api/**}：白名单与静态资源由
 * {@link PermissionInterceptor} 内部按 {@link SecurityWhitelist} 与请求方法自行放行，
 * 在这里再做一次路径裁剪只会让两处规则有机会漂移。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    public WebMvcConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/**");
    }
}
