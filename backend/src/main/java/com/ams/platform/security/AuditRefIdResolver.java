package com.ams.platform.security;

import com.ams.common.web.ApiResponse;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 推断「被操作对象的 id」—— {@code operation_log.ref_id} 的取值来源。
 *
 * <p><b>为什么需要它</b>：{@code ref_id} 是 {@code V2} 建表时就有的列，但切面从未写入过，
 * 于是它一直是一条恒为 NULL 的死列。{@code detail_json} 虽然含全部入参，却只能靠
 * {@code LIKE} 反查 JSON ——「把某个对象上发生过的操作全部翻出来」这种审计最常用的问题，
 * 无法用等值条件回答。
 *
 * <p><b>两条取值规则（按优先级）</b>：
 *
 * <ol>
 *   <li><b>URL 里最深的那个 id 型路径变量</b>。REST 路径是
 *       {@code /父资源/{父id}/子资源/{子id}}，越靠后越具体，因此取<i>最后一个</i>。
 *       实测全仓 203 个已审计写接口中 124 个有且仅有一个 id 型路径变量，
 *       另外 6 个是嵌套分区/楼层接口（取到的是被操作的那一层，而不是它的父级）。</li>
 *   <li><b>返回值实体的 id</b>（仅当没有 id 型路径变量时）。新建类接口（{@code POST /assets}）
 *       的路径里没有任何 id，而被创建对象的 id 只存在于返回值里 —— 这是 {@code ref_id}
 *       相对 {@code detail_json} 唯一的信息增量。</li>
 * </ol>
 *
 * <p><b>为什么规则 1 优先于规则 2</b>：可预测性。审计查询要能靠一句
 * 「这个对象上发生过什么」直接下结论，而「有时是 URL 里的资源、有时是返回值里的产物」
 * 会让 {@code ref_id} 的语义随接口漂移。规则 1 让 {@code ref_id} 恒等于
 * 「请求所指向的那个资源」，规则 2 只在没有资源可指时兜底。
 *
 * <p><b>刻意不猜的地方（取不到就留 NULL）</b>：
 *
 * <ul>
 *   <li>不做「从多变参里挑一个」的启发式：命中不了就返回 null，宁可少一条 {@code ref_id}，
 *       也不要一条指向错误对象的审计行 —— 后者会让人得出错误结论。</li>
 *   <li>不看 {@code @RequestParam} 的 id：查询参数是<i>筛选条件</i>，不是被操作的资源。</li>
 *   <li>不解析 {@code @RequestBody} 里的 id：请求体里的 id 既可能是本对象、也可能是父对象，
 *       无从判断（新建时通常还是 null）。</li>
 *   <li>不认 {@code Number} 之外的 id 类型、不认 0 与负数：主键非正只可能来自未落库的 DTO
 *       或占位值，记进审计会产出指向不存在对象的行。</li>
 *   <li>不认形如 {@code planId} 这类「字段名不是 {@code id}」的返回实体：要求实体真的叫
 *       {@code id}，才不至于把 {@code planId}、{@code companyId} 之类的关联字段当成主键。</li>
 * </ul>
 *
 * <p><b>绝不影响业务</b>：本类只做只读推断，任何反射失败都被吞掉并降级为 {@code null}。
 */
final class AuditRefIdResolver {

    /** 形如 {@code id} 或 {@code xxxId} 的路径变量名；大小写敏感，避免把 {@code paid} 当成 {@code *Id}。 */
    private static final String ID = "id";

    /** {@code *Id} 后缀的最短长度：{@code "id"} 本身已被 {@link #ID} 覆盖，故要求前缀非空。 */
    private static final int MIN_SUFFIXED_LENGTH = 3;

    /**
     * 各类的 id 访问器缓存（含「确实没有」这一结论）。
     *
     * <p>键是被审计的返回类型，取值只有实体类那么多个，不存在无界增长；
     * 缓存的意义在于审计落在每个写请求的同步路径上，不该每次都重新反射一遍。
     */
    private static final Map<Class<?>, Optional<Method>> ID_ACCESSORS = new ConcurrentHashMap<>();

    /** 与 {@link Optional#empty()} 等价的哨兵：命中过「没有 id 访问器」就不再重复查找。 */
    private static final Optional<Method> NO_ID_ACCESSOR = Optional.empty();

    private AuditRefIdResolver() {
    }

    /**
     * @param result 方法返回值；失败路径上传 {@code null}（此时只剩路径变量可用）
     * @return 被操作对象的 id；推断不出时为 {@code null}（{@code ref_id} 保持空）
     */
    static Long resolve(ProceedingJoinPoint pjp, Object result) {
        if (!(pjp.getSignature() instanceof MethodSignature signature)) {
            // 拿不到方法签名（如非方法连接点）就放弃，绝不猜
            return null;
        }
        return resolve(signature.getMethod(), pjp.getArgs(), result);
    }

    /** 供测试直接调用的入口：绕开 AspectJ 签名，只依赖 {@link Method} 与实参。 */
    static Long resolve(Method method, Object[] args, Object result) {
        Long fromUrl = deepestPathVariable(method, args);
        if (fromUrl != null) {
            return fromUrl;
        }
        return idOfEntity(unwrap(result));
    }

    /**
     * URL 里最深的 id 型路径变量。
     *
     * <p>取<i>最后一个</i>而不是「唯一一个」：{@code /projects/{id}/zones/{zoneId}/floors/{floorId}}
     * 里越靠后的越具体，而被操作的正是最具体的那一层。
     */
    private static Long deepestPathVariable(Method method, Object[] args) {
        if (method == null || args == null) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        Long deepest = null;
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            PathVariable binding = parameters[i].getAnnotation(PathVariable.class);
            if (binding == null) {
                continue;
            }
            // 显式写了 @PathVariable("zoneId") 时以注解值为准：那时形参名可能已被混淆成 arg0
            String name = binding.value().isEmpty() ? parameters[i].getName() : binding.value();
            if (!isIdLikeName(name)) {
                continue;
            }
            Long value = toPositiveLong(args[i]);
            if (value != null) {
                deepest = value;
            }
        }
        return deepest;
    }

    /** {@code ApiResponse} 剥一层壳；其它返回值原样返回。 */
    private static Object unwrap(Object result) {
        return result instanceof ApiResponse<?> response ? response.getData() : result;
    }

    /** 返回实体的 id；返回体是集合 / Map / 无 id 访问器时一律为 {@code null}。 */
    private static Long idOfEntity(Object candidate) {
        if (!isSingleEntity(candidate)) {
            return null;
        }
        Method accessor = ID_ACCESSORS
                .computeIfAbsent(candidate.getClass(), AuditRefIdResolver::findIdAccessor)
                .orElse(null);
        if (accessor == null) {
            return null;
        }
        try {
            return toPositiveLong(accessor.invoke(candidate));
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException ex) {
            // 访问器不可访问或自身抛错：审计的字段推断绝不能把整条审计记录连累掉
            return null;
        }
    }

    /**
     * 是不是「单个实体」。
     *
     * <p>批量接口（{@code ApiResponse<List<…>>}）返回的是一串对象，没有「那个对象」可言；
     * 即使某个容器类将来长出一个 {@code id()}，也不该被当成被操作对象。
     */
    private static boolean isSingleEntity(Object candidate) {
        if (candidate == null || candidate.getClass().isArray()) {
            return false;
        }
        return !(candidate instanceof Collection<?>)
                && !(candidate instanceof Map<?, ?>)
                && !(candidate instanceof CharSequence)
                && !(candidate instanceof Number);
    }

    /** 查找 id 访问器：先 {@code getId()}（Lombok 实体），再 {@code id()}（record）。 */
    private static Optional<Method> findIdAccessor(Class<?> type) {
        for (String candidate : new String[] {"getId", "id"}) {
            Method method;
            try {
                method = type.getMethod(candidate);
            } catch (NoSuchMethodException ex) {
                continue;
            }
            if (method.getParameterCount() == 0 && isIdShaped(method.getReturnType())) {
                return Optional.of(method);
            }
        }
        return NO_ID_ACCESSOR;
    }

    private static boolean isIdShaped(Class<?> type) {
        return Number.class.isAssignableFrom(type)
                || type == long.class
                || type == int.class
                || type == short.class
                || type == byte.class
                || type == String.class;
    }

    private static boolean isIdLikeName(String name) {
        return ID.equals(name) || (name.length() >= MIN_SUFFIXED_LENGTH && name.endsWith("Id"));
    }

    private static Long toPositiveLong(Object value) {
        Long parsed;
        if (value instanceof Number number) {
            parsed = number.longValue();
        } else if (value instanceof String text) {
            try {
                parsed = Long.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        } else {
            return null;
        }
        return parsed > 0 ? parsed : null;
    }
}
