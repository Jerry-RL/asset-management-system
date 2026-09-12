package com.ams.platform.security;

import com.ams.modules.system.entity.Menu;
import com.ams.modules.system.mapper.MenuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作级权限接入台账（设计 6.3）：启动时扫描全部 {@link RequiresPerm}，产出「已强制校验的
 * {@code menu:action} 清单」，供 {@code GET /system/permission-actions} 与
 * {@code GET /system/roles/{roleId}/permissions} 下发，驱动前端矩阵上的「未强制校验」标识。
 *
 * <p>存在的理由：这份清单如果由前端硬编码，必然与后端注解漂移 —— 管理员会以为已生效的动作
 * 其实没有拦截。由注解扫描生成则不可能漂移。
 *
 * <p><strong>启动即校验</strong>：注解值格式非法、动作不在 {@link PermissionAction} 词表内时
 * 直接抛异常终止启动。这类错误（例如把 {@code system.menu} 写成 {@code system.menus}）
 * 一旦上线就是「接口对所有角色 403」，比启动失败严重得多。
 */
@Component
public class PermissionRegistry implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PermissionRegistry.class);

    private final ApplicationContext applicationContext;
    private final MenuMapper menuMapper;
    private final boolean strictPerm;

    /** 已接入 {@code menu:action} 的强制校验清单（只读，启动后不再变化）。 */
    private volatile Set<String> enforced = Set.of();

    public PermissionRegistry(
            ApplicationContext applicationContext,
            MenuMapper menuMapper,
            @Value("${ams.security.strict-perm:false}") boolean strictPerm) {
        this.applicationContext = applicationContext;
        this.menuMapper = menuMapper;
        this.strictPerm = strictPerm;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> found = new TreeSet<>();
        for (String beanName : applicationContext.getBeanNamesForAnnotation(RestController.class)) {
            collect(applicationContext.getType(beanName), found);
        }
        for (String beanName : applicationContext.getBeanNamesForAnnotation(Controller.class)) {
            collect(applicationContext.getType(beanName), found);
        }
        assertMenuCodesExist(found);
        // 保持排序：该清单会直接进入接口响应，顺序不稳定会让前端缓存与快照对比失真
        this.enforced = Collections.unmodifiableSet(new TreeSet<>(found));
        log.info("操作级权限接入台账：{} 个 menu:action 已强制校验 -> {}", enforced.size(), enforced);
    }

    /**
     * 校验注解里的 {@code menuCode} 在 {@code menu} 表中真实存在。
     *
     * <p>这是本设计最危险的一类错字：{@code system.menu} 写成 {@code system.menus} 时，
     * 权限矩阵里勾的是一个不存在的权限点、断言用的是另一个字符串，结果是
     * <strong>该接口对所有非超管角色永久 403</strong>，而且不报任何错。
     * 因此这里宁可让应用启动失败，也不让它在线上以 403 的形式表现出来。
     *
     * <p><strong>查不到表 / 表为空时不失败，只告警</strong>，两种情况都不是「编码写错」：
     * <ul>
     *   <li>测试 profile 关闭了 Flyway（H2 内存库无 schema），启动即失败会让
     *       {@code contextLoads} 这类用例全部报错，而它与权限配置无关；</li>
     *   <li>表为空说明迁移未执行 —— 此时任何权限点都无法被授予，启动失败只会把
     *       「迁移没跑」这个更简单的原因掩盖成权限配置问题。</li>
     * </ul>
     * 表存在且有数据时的校验才是有效的那个分支，也正是线上真正会走到的分支。
     */
    private void assertMenuCodesExist(Set<String> permissions) {
        Set<String> known;
        try {
            known = new HashSet<>(menuMapper
                    .selectList(new LambdaQueryWrapper<Menu>().select(Menu::getCode))
                    .stream()
                    .map(Menu::getCode)
                    .toList());
        } catch (DataAccessException ex) {
            log.warn("@RequiresPerm 校验跳过：读取 menu 表失败（未迁移 / 测试环境关闭 Flyway？）：{}",
                    ex.getMessage());
            return;
        }
        if (known.isEmpty()) {
            log.warn("@RequiresPerm 校验跳过：menu 表为空（迁移未执行？）。当前 {} 个权限点无法核对",
                    permissions.size());
            return;
        }
        Set<String> unknown = new TreeSet<>();
        for (String permission : permissions) {
            String code = permission.substring(0, permission.lastIndexOf(':'));
            if (!known.contains(code)) {
                unknown.add(permission);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "@RequiresPerm 引用了 menu 表中不存在的编码，这些接口将对所有角色 403："
                            + unknown + "；可用编码=" + new TreeSet<>(known));
        }
    }

    private void collect(Class<?> beanType, Set<String> found) {
        if (beanType == null) {
            return;
        }
        Class<?> target = AopUtils.getTargetClass(beanType);
        // 类级声明：方法级未标注时生效
        RequiresPerm classLevel = AnnotatedElementUtils.findMergedAnnotation(target, RequiresPerm.class);
        if (classLevel != null) {
            found.add(validate(classLevel.value(), target.getName()));
        }
        // getAllDeclaredMethods：包含父类声明的方法，避免注解落在继承来的接口上被漏扫
        ReflectionUtils.getAllDeclaredMethods(target).forEach(method -> {
            RequiresPerm methodLevel = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPerm.class);
            if (methodLevel != null) {
                found.add(validate(methodLevel.value(), target.getName() + "#" + method.getName()));
            }
        });
    }

    /** 校验并规范化注解值；非法直接抛异常终止启动。 */
    private String validate(String raw, String where) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("@RequiresPerm 取值不能为空：" + where);
        }
        String value = raw.trim();
        int idx = value.lastIndexOf(':');
        if (idx <= 0 || idx == value.length() - 1) {
            throw new IllegalStateException(
                    "@RequiresPerm 必须形如 menuCode:action，实际为 " + value + "：" + where);
        }
        String action = value.substring(idx + 1);
        if (!PermissionAction.isValid(action)) {
            throw new IllegalStateException(
                    "@RequiresPerm 动作不在词表 " + PermissionAction.codes() + " 内："
                            + value + "：" + where);
        }
        return value;
    }

    /** 全部已接入的 {@code menu:action}（即真正由 {@code @RequiresPerm} 强制的那部分）。 */
    public Set<String> enforcedPermissions() {
        return enforced;
    }

    /**
     * 下发前端矩阵用的「已强制校验」清单，已计入 {@code strict-perm} 档位。
     *
     * <p>只看 {@link #enforcedPermissions()} 会在严格档下<strong>低报</strong>：那时所有
     * 变更类接口无论有没有注解都会被拒绝，矩阵若仍标成「未生效」，管理员会去补一个
     * 早已生效的注解。反之在默认档下不能高报，否则管理员以为已拦截、实际裸奔。
     *
     * @param menuCodes 参与矩阵的菜单编码（严格档需要据此展开变更类动作）
     */
    public List<String> effectiveEnforced(Collection<String> menuCodes) {
        Set<String> result = new TreeSet<>(enforced);
        if (strictPerm && menuCodes != null) {
            for (String menuCode : menuCodes) {
                if (menuCode == null) {
                    continue;
                }
                for (String action : PermissionAction.mutatingCodes()) {
                    result.add(menuCode + ":" + action);
                }
            }
        }
        return List.copyOf(result);
    }
}
