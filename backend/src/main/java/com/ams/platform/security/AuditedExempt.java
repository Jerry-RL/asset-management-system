package com.ams.platform.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计豁免（FR-COM-004 的显式出口）。
 *
 * <p>{@code scripts/check-audit-coverage.mjs} 要求每个写接口必须带 {@link Audited} 或本注解，
 * 因此豁免理由<b>必须写在端点旁</b>、随代码评审，而不是藏在某个脚本的白名单文件里 ——
 * 白名单文件里的豁免没人会在改动那个接口时回来看一眼。
 *
 * <p>典型豁免场景：无登录用户的第三方回调（会产出 {@code username=null} 的幽灵行）、
 * 只是借 POST 传 body 的只读预览、以及高频低价值操作（落库会淹没真正的关键操作）。
 *
 * <p><b>只影响「记不记审计」，不影响鉴权</b>：豁免端点的 {@code @RequiresPerm} 与
 * 拦截行为一律不变。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedExempt {

    /**
     * 豁免理由。
     *
     * <p>静态守卫要求非空且长度 ≥ 8：{@code @AuditedExempt("TODO")} 这类占位不算理由，
     * 它会让豁免在几个月后变成没人知道为什么存在的例外。
     */
    String value();
}
