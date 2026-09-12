package com.ams.platform.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作级权限声明（设计 6.1）。
 *
 * <p>取值固定为 {@code menuCode:action}，其中 {@code menuCode} 必须是 {@code menu} 表里真实存在的
 * {@code code}，{@code action} 必须取自 {@link PermissionAction}。两处任一写错都会导致接口
 * 对所有角色 403 —— 因此 {@link PermissionRegistry} 在启动时逐个校验并在格式非法时直接
 * 让应用启动失败，而不是等到线上调用时才 403。
 *
 * <p>标注在类上表示该类所有接口共用该权限；标注在方法上覆盖类级声明。
 *
 * @see PermissionRegistry
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPerm {

    /** 形如 {@code asset.ledger:update}。 */
    String value();
}
