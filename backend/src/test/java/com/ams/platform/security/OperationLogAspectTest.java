package com.ams.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ams.common.web.ApiResponse;
import com.ams.modules.asset.entity.Asset;
import com.ams.modules.system.entity.OperationLog;
import com.ams.modules.system.mapper.OperationLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 操作审计切面的行为守卫（设计 §7）。
 *
 * <p>直接调 {@code around}，不跑 Spring 上下文：这里要验证的是「成败怎么记」与
 * 「审计故障会不会影响业务」，与容器无关。
 *
 * <p>四条重点：
 * <ol>
 *   <li><b>成败由调用点显式传入</b>，不由 {@code error == null} 反推。反推会在
 *       {@code t.getMessage()} 为 null 时（如裸 {@code NullPointerException}）
 *       把失败记成成功 —— 审计里最不能出错的方向；</li>
 *   <li>业务异常<b>必须原样上抛</b>：审计绝不改变业务行为（不改异常类型、不包装）；</li>
 *   <li>落库失败<b>必须吞掉但不能静默</b>：不抛异常，同时留下 warn 日志；</li>
 *   <li>{@code error} 截断到列宽（VARCHAR(500)），否则超长 message 会让 insert 失败，
 *       反而把整条审计记录丢掉。</li>
 * </ol>
 */
class OperationLogAspectTest {

    @Audited(module = "asset", action = "delete")
    private void annotated() {
        // 仅用于从真实注解上取 Audited 实例，保证夹具与生产用法一致
    }

    private static final Audited ASSET_DELETE = readAudited();

    private final OperationLogMapper mapper = mock(OperationLogMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OperationLogAspect aspect = new OperationLogAspect(mapper, objectMapper);

    @BeforeEach
    void setUp() {
        // TraceIdUtil 是 ThreadLocal，测试里没有 TraceIdFilter 兜底，必须自己喂
        com.ams.common.web.TraceIdUtil.set("trace-under-test");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        com.ams.common.web.TraceIdUtil.clear();
    }

    /**
     * 从真实注解上取实例。
     *
     * <p>刻意<b>不</b>用「动态代理手搓 Audited」：那样会绕过注解本身，
     * 注解的属性名 / 默认值改了也测不出来。
     */
    private static Audited readAudited() {
        try {
            return OperationLogAspectTest.class
                    .getDeclaredMethod("annotated")
                    .getAnnotation(Audited.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProceedingJoinPoint pjp(Object result, Throwable toThrow) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        if (toThrow == null) {
            when(pjp.proceed()).thenReturn(result);
        } else {
            when(pjp.proceed()).thenThrow(toThrow);
        }
        when(pjp.getArgs()).thenReturn(new Object[] {"asset-1"});
        return pjp;
    }

    /**
     * 带真实方法签名的连接点。
     *
     * <p>{@code ref_id} 的推断依赖「形参的名字与注解」，这两样都伪造不出来 —— 必须给一个
     * 真方法，因此下面那两个夹具方法的形参名与生产代码同源（都靠 {@code -parameters}）。
     */
    private static ProceedingJoinPoint signedPjp(Method method, Object[] args, Object result)
            throws Throwable {
        return signedPjp(method, args, result, null);
    }

    /** 同上，但让业务方法抛异常：失败路径也要能取到 ref_id。 */
    private static ProceedingJoinPoint failingPjp(Method method, Object[] args, Throwable toThrow)
            throws Throwable {
        return signedPjp(method, args, null, toThrow);
    }

    private static ProceedingJoinPoint signedPjp(Method method, Object[] args, Object result,
            Throwable toThrow) throws Throwable {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        if (toThrow == null) {
            when(pjp.proceed()).thenReturn(result);
        } else {
            when(pjp.proceed()).thenThrow(toThrow);
        }
        return pjp;
    }

    @SuppressWarnings("unused")
    private void annotatedUpdate(@PathVariable Long assetId) {
        // 夹具：有 id 型路径变量的写接口
    }

    @SuppressWarnings("unused")
    private void annotatedCreate(String body) {
        // 夹具：没有路径变量的新建接口
    }

    private static Method updateMethod() {
        return declared("annotatedUpdate", Long.class);
    }

    private static Method createMethod() {
        return declared("annotatedCreate", String.class);
    }

    private static Method declared(String name, Class<?>... parameters) {
        try {
            return OperationLogAspectTest.class.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void login(String username, Long userId) {
        var user = LoginUser.builder()
                .userId(userId)
                .username(username)
                .name(username)
                .companyId(1L)
                .homeCompanyId(1L)
                .departmentId(1L)
                .clientType("admin")
                .roles(Set.of("operator"))
                .permissions(Set.of())
                .dataScope("company")
                .companyScoped(true)
                .excludedCompanyIds(Set.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private OperationLog captured() {
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // 成败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正常返回 -> success=true，error 为 null，业务返回值不变")
    void recordsSuccess() throws Throwable {
        login("admin", 1L);

        Object returned = aspect.around(pjp("deleted", null), ASSET_DELETE);

        assertThat(returned).isEqualTo("deleted");
        OperationLog row = captured();
        assertThat(row.getSuccess()).isTrue();
        assertThat(row.getError()).isNull();
        assertThat(row.getModule()).isEqualTo("asset");
        assertThat(row.getAction()).isEqualTo("delete");
        assertThat(row.getUserId()).isEqualTo(1L);
        assertThat(row.getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("业务抛异常 -> success=false，error 记原因，且异常原样上抛")
    void recordsFailureAndRethrows() throws Throwable {
        login("admin", 1L);
        IllegalStateException boom = new IllegalStateException("资产已被占用");

        assertThatThrownBy(() -> aspect.around(pjp(null, boom), ASSET_DELETE))
                .as("异常必须原样上抛：审计不改变业务行为")
                .isSameAs(boom);

        OperationLog row = captured();
        assertThat(row.getSuccess()).isFalse();
        assertThat(row.getError()).isEqualTo("资产已被占用");
    }

    @Test
    @DisplayName("异常 message 为 null（裸 NPE）仍记 success=false —— 不得用 error==null 反推成败")
    void nullMessageStillCountsAsFailure() throws Throwable {
        login("admin", 1L);
        NullPointerException npe = new NullPointerException();

        assertThatThrownBy(() -> aspect.around(pjp(null, npe), ASSET_DELETE))
                .isSameAs(npe);

        OperationLog row = captured();
        assertThat(row.getSuccess())
                .as("若用 error==null 反推，这条会把失败记成成功 —— 审计里最不能出错的方向")
                .isFalse();
        assertThat(row.getError()).isNull();
    }

    @Test
    @DisplayName("未登录（如定时任务触发）也能落库：username/userId 为 null 而不是整条丢掉")
    void recordsAnonymousCaller() throws Throwable {
        SecurityContextHolder.clearContext();

        aspect.around(pjp("ok", null), ASSET_DELETE);

        OperationLog row = captured();
        assertThat(row.getUserId()).isNull();
        assertThat(row.getUsername()).isNull();
        assertThat(row.getSuccess()).isTrue();
    }

    // ------------------------------------------------------------------
    // 截断与形状
    // ------------------------------------------------------------------

    @Test
    @DisplayName("error 超过列宽 500 -> 截断，否则 insert 失败会把整条审计记录丢掉")
    void truncatesErrorToColumnWidth() throws Throwable {
        login("admin", 1L);
        String longMessage = "x".repeat(900);

        assertThatThrownBy(() -> aspect.around(
                        pjp(null, new RuntimeException(longMessage)), ASSET_DELETE))
                .isInstanceOf(RuntimeException.class);

        assertThat(captured().getError()).hasSize(500);
    }

    @Test
    @DisplayName("detail_json 只放 args：error 已独立成列，两处都写会产生两套口径")
    void detailJsonOnlyCarriesArgs() throws Throwable {
        login("admin", 1L);

        aspect.around(pjp("ok", null), ASSET_DELETE);

        String detail = captured().getDetailJson();
        assertThat(detail).contains("args");
        assertThat(detail)
                .as("error 必须只出现在 error 列，不得再塞回 detail_json")
                .doesNotContain("\"error\"");
    }

    @Test
    @DisplayName("入参里的密码 / token 必须脱敏（审计台账不能成为泄密渠道）")
    void sanitizesSensitiveArgs() throws Throwable {
        login("admin", 1L);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        when(pjp.getArgs()).thenReturn(new Object[] {"password=Secr3t&token=abc123"});

        aspect.around(pjp, ASSET_DELETE);

        String detail = captured().getDetailJson();
        assertThat(detail).doesNotContain("Secr3t").doesNotContain("abc123");
        assertThat(detail).contains("***");
    }

    @Test
    @DisplayName("traceId 必须从上下文带过来：没有它，审计行无法与 app_log 关联")
    void stampsTraceId() throws Throwable {
        login("admin", 1L);

        aspect.around(pjp("ok", null), ASSET_DELETE);

        OperationLog row = captured();
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getTraceId()).isEqualTo("trace-under-test");
    }

    // ------------------------------------------------------------------
    // 审计故障不影响业务
    // ------------------------------------------------------------------

    @Test
    @DisplayName("落库失败 -> 业务照常返回（审计是旁路，不能变成业务 500）")
    void insertFailureDoesNotBreakBusiness() throws Throwable {
        login("admin", 1L);
        when(mapper.insert(any(OperationLog.class)))
                .thenThrow(new RuntimeException("数据库连接池耗尽"));

        Object returned = aspect.around(pjp("deleted", null), ASSET_DELETE);

        assertThat(returned)
                .as("审计写入失败绝不能变成业务失败")
                .isEqualTo("deleted");
    }

    @Test
    @DisplayName("落库失败且业务也失败 -> 上抛的是业务异常，不是审计异常")
    void insertFailureOnBusinessFailureRethrowsBusinessOne() throws Throwable {
        login("admin", 1L);
        when(mapper.insert(any(OperationLog.class)))
                .thenThrow(new RuntimeException("数据库连接池耗尽"));
        IllegalStateException business = new IllegalStateException("业务失败");

        assertThatThrownBy(() -> aspect.around(pjp(null, business), ASSET_DELETE))
                .as("把审计异常上抛会掩盖真正的业务原因，排查方向会被带偏")
                .isSameAs(business);
    }

    // ------------------------------------------------------------------
    // ref_id（被操作对象）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有 id 型路径变量 -> ref_id 取它，让「这个对象上发生过什么」可以等值查出")
    void refIdComesFromPathVariable() throws Throwable {
        login("admin", 1L);

        aspect.around(signedPjp(updateMethod(), new Object[] {42L}, "ok"), ASSET_DELETE);

        assertThat(captured().getRefId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("失败的操作同样带 ref_id：审计最想看的正是「谁想动哪个对象、为什么没成功」")
    void refIdIsRecordedOnFailure() throws Throwable {
        login("admin", 1L);
        IllegalStateException boom = new IllegalStateException("资产已被占用");

        assertThatThrownBy(() -> aspect.around(
                        failingPjp(updateMethod(), new Object[] {42L}, boom), ASSET_DELETE))
                .isSameAs(boom);

        OperationLog row = captured();
        assertThat(row.getSuccess()).isFalse();
        assertThat(row.getRefId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("新建接口无路径变量 -> ref_id 取返回值实体的 id（入参里根本没有这个信息）")
    void refIdFallsBackToReturnedEntity() throws Throwable {
        login("admin", 1L);
        Asset created = new Asset();
        created.setId(31L);

        aspect.around(
                signedPjp(createMethod(), new Object[] {"{}"}, ApiResponse.ok(created, null)),
                ASSET_DELETE);

        assertThat(captured().getRefId()).isEqualTo(31L);
    }

    @Test
    @DisplayName("推断不出时留 NULL 而不是猜：批量操作没有「那个对象」可言")
    void refIdIsNullWhenNotInferable() throws Throwable {
        login("admin", 1L);

        // 路径变量不是数字（业务键），返回值也不是单个实体
        aspect.around(signedPjp(createMethod(), new Object[] {"{}"}, "ok"), ASSET_DELETE);

        assertThat(captured().getRefId()).isNull();
        assertThat(captured().getModule())
                .as("ref_id 推断不出来也绝不能影响整条审计记录落库")
                .isEqualTo("asset");
    }

    /**
     * 走**真实 AspectJ 织入**而不是手搓 {@link ProceedingJoinPoint}。
     *
     * <p>上面几条用例都在桩上跑，唯一没法用桩证明的假设就是「真实织入时
     * {@code MethodSignature.getMethod()} 拿到的确实带形参注解的那个方法」——
     * 若它返回的是代理方法或桥接方法，{@code @PathVariable} 就全都读不到，
     * 规则 1 会在生产上静默失效、而单元测试照样全绿。因此这里真的织一次。
     */
    @Test
    @DisplayName("真实织入（AspectJProxyFactory）也能取到路径变量：否则规则 1 会在生产上静默失效")
    void resolvesThroughRealWeaving() {
        login("admin", 1L);
        AspectJProxyFactory factory = new AspectJProxyFactory(new RealController());
        factory.addAspect(aspect);
        RealController proxy = factory.getProxy();

        String returned = proxy.update(9L);

        assertThat(returned).isEqualTo("ok");
        assertThat(captured().getRefId()).isEqualTo(9L);
    }

    /** 真实控制器形状的夹具：注解 + 路径变量都在方法上。 */
    public static class RealController {

        @Audited(module = "asset", action = "update")
        public String update(@PathVariable Long assetId) {
            return "ok";
        }
    }

    @Test
    @DisplayName("落库失败仍会尝试写入一次（不因失败而提前跳过）")
    void stillAttemptsInsert() throws Throwable {
        login("admin", 1L);
        when(mapper.insert(any(OperationLog.class))).thenThrow(new RuntimeException("boom"));

        aspect.around(pjp("ok", null), ASSET_DELETE);

        verify(mapper).insert(any(OperationLog.class));
    }
}
