package com.ams.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.common.web.ApiResponse;
import com.ams.common.web.PageResult;
import com.ams.modules.asset.entity.Asset;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * {@code ref_id} 推断规则的行为守卫。
 *
 * <p>这一组用例的价值不在「能取到 id」，而在**取不到时确实返回 null**：
 * 一条指向错误对象的审计行会让人得出错误结论，比少一条 {@code ref_id} 严重得多。
 * 因此每条「不猜」的规则都配了一个反向用例。
 */
class AuditRefIdResolverTest {

    // ------------------------------------------------------------------
    // 夹具：形参名必须与生产代码一样真实，规则的 1 完全依赖它们
    // ------------------------------------------------------------------

    @SuppressWarnings("unused")
    private static class Fixture {

        void updateAsset(@PathVariable Long assetId) {
        }

        void deleteZone(@PathVariable Long id, @PathVariable Long zoneId) {
        }

        void deleteZoneFloor(@PathVariable Long id, @PathVariable Long zoneId,
                @PathVariable Long floorId) {
        }

        void lockMonth(@PathVariable String period) {
        }

        void explicitName(@PathVariable("zoneId") Long whatever) {
        }

        void createProject(String body) {
        }

        void updateStatus(@PathVariable Long id, String status) {
        }

        void zeroId(@PathVariable Long id) {
        }

        void negativeId(@PathVariable Long id) {
        }

        void numericStringId(@PathVariable Long id) {
        }
    }

    private static Method method(String name) {
        for (Method candidate : Fixture.class.getDeclaredMethods()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalStateException("夹具方法不存在：" + name);
    }

    /** Lombok 风格的实体（{@code getId()}）。 */
    private static class PojoEntity {
        private Long id;

        PojoEntity(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }
    }

    /** {@code id} 字段名不是 {@code id} 的实体：不得被当成主键。 */
    private static class RelatedOnly {
        @SuppressWarnings("unused")
        public Long getPlanId() {
            return 77L;
        }
    }

    /** 访问器自身抛错：推断失败必须降级为 null，不能把审计连累掉。 */
    private static class ExplodingId {
        public Long getId() {
            throw new IllegalStateException("boom");
        }
    }

    /** {@code longValue()} 自身抛错的 Number：id 形态合法，取值失败。 */
    private static class ExplodingNumber extends Number {
        @Override
        public int intValue() {
            return 0;
        }

        @Override
        public long longValue() {
            throw new ArithmeticException("这一列存了非数字内容");
        }

        @Override
        public float floatValue() {
            return 0;
        }

        @Override
        public double doubleValue() {
            return 0;
        }
    }

    private static class ExplodingNumberEntity {
        @SuppressWarnings("unused")
        public Number getId() {
            return new ExplodingNumber();
        }
    }

    /** 返回值里 id 是 String 类型（历史接口偶见）。 */
    private static class StringIdEntity {
        @SuppressWarnings("unused")
        public String getId() {
            return "42";
        }
    }

    private record RecordEntity(Long id, String name) {
    }

    // ------------------------------------------------------------------
    // 前置假设：形参名可用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("编译必须带 -parameters：形参名丢了，规则 1 会静默退化成「永远取不到」")
    void parameterNamesAreAvailable() {
        assertThat(method("updateAsset").getParameters()[0].getName())
                .as("形参名取自 MethodParameters 属性；若这里拿到 arg0，说明 -parameters 被关掉，"
                        + "ref_id 会静默退化成空列，而编译与其它测试照样全绿")
                .isEqualTo("assetId");
    }

    // ------------------------------------------------------------------
    // 规则 1：URL 里最深的 id 型路径变量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("唯一的 id 型路径变量 -> 取它（与返回值无关，失败路径同样可用）")
    void singlePathVariable() {
        assertThat(AuditRefIdResolver.resolve(method("updateAsset"), new Object[] {7L}, null))
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("多个 id 型路径变量 -> 取最后一个（/父/{父id}/子/{子id} 里最具体的那层）")
    void nestedPathVariablesTakeDeepest() {
        assertThat(AuditRefIdResolver.resolve(
                        method("deleteZoneFloor"), new Object[] {1L, 2L, 3L}, null))
                .isEqualTo(3L);
        assertThat(AuditRefIdResolver.resolve(method("deleteZone"), new Object[] {1L, 2L}, null))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("路径变量里有值 -> 优先于返回值实体（ref_id 恒等于「请求指向的资源」）")
    void pathVariableWinsOverReturnedEntity() {
        assertThat(AuditRefIdResolver.resolve(
                        method("deleteZoneFloor"), new Object[] {1L, 2L, 3L},
                        ApiResponse.ok(new PojoEntity(999L), null)))
                .as("可预测性：否则 ref_id 的语义会随接口在「URL 资源」与「返回值产物」之间漂移")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("显式 @PathVariable(\"zoneId\") -> 用注解值，不依赖形参名")
    void explicitPathVariableName() {
        assertThat(AuditRefIdResolver.resolve(method("explicitName"), new Object[] {9L}, null))
                .isEqualTo(9L);
    }

    @Test
    @DisplayName("非 id 型路径变量（period / type 这类业务键）-> 不取")
    void nonIdPathVariableIsIgnored() {
        assertThat(AuditRefIdResolver.resolve(method("lockMonth"), new Object[] {"2026-09"}, null))
                .as("把业务键当主键写进 ref_id，等于制造一条指向不存在对象的审计行")
                .isNull();
    }

    @Test
    @DisplayName("普通 @PathVariable 旁边有非路径变量 -> 不受影响")
    void ignoresNonPathVariableParameters() {
        assertThat(AuditRefIdResolver.resolve(
                        method("updateStatus"), new Object[] {5L, "approved"}, null))
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("id 为 0 / 负数 / null -> 不取")
    void nonPositivePathVariableIsRejected() {
        assertThat(AuditRefIdResolver.resolve(method("zeroId"), new Object[] {0L}, null)).isNull();
        assertThat(AuditRefIdResolver.resolve(method("negativeId"), new Object[] {-3L}, null)).isNull();
        assertThat(AuditRefIdResolver.resolve(method("negativeId"), new Object[] {null}, null)).isNull();
    }

    @Test
    @DisplayName("String 形态的数字 id -> 取（历史接口偶见 VARCHAR 主键）")
    void numericStringPathVariableIsAccepted() {
        assertThat(AuditRefIdResolver.resolve(
                        method("numericStringId"), new Object[] {"12"}, null))
                .isEqualTo(12L);
    }

    // ------------------------------------------------------------------
    // 规则 2：返回值实体的 id（仅在 URL 里没有 id 资源时）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无路径变量 + ApiResponse 包着实体 -> 取实体 id")
    void fallsBackToReturnedEntity() {
        Asset asset = new Asset();
        asset.setId(31L);

        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"}, ApiResponse.ok(asset, null)))
                .isEqualTo(31L);
    }

    @Test
    @DisplayName("无路径变量 + record 的 id() 访问器 -> 取实体 id")
    void readsRecordAccessor() {
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(new RecordEntity(8L, "zf"), null)))
                .isEqualTo(8L);
    }

    @Test
    @DisplayName("id 是 String 类型的返回实体 -> 取")
    void readsStringIdFromEntity() {
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(new StringIdEntity(), null)))
                .isEqualTo(42L);
    }

    @Test
    @DisplayName("实体只有 planId / companyId -> 不取（字段名不是 id 就不是主键）")
    void relatedIdFieldIsNotTakenAsPrimaryKey() {
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(new RelatedOnly(), null)))
                .isNull();
    }

    @Test
    @DisplayName("批量返回（List / PageResult / Map）-> 不取，没有「那个对象」可言")
    void bulkResultsAreNotTaken() {
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(List.of(new RecordEntity(1L, "a")), null)))
                .isNull();
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(PageResult.of(List.of(), 0, 1, 20), null)))
                .isNull();
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(Map.of("id", 42L), null)))
                .as("Map 里的键没有类型保证，把它当主键等于猜")
                .isNull();
    }

    @Test
    @DisplayName("实体 id 非正 -> 不取（新建接口常返回未落库的 DTO，id 为 0）")
    void nonPositiveEntityIdIsRejected() {
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(new PojoEntity(0L), null)))
                .isNull();
    }

    @Test
    @DisplayName("返回值不是 ApiResponse（如 ResponseEntity / Void）-> 不剥壳，取不到就 null")
    void nonApiResponseResult() {
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"}, "ok"))
                .isNull();
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"}, ApiResponse.ok(null, null)))
                .isNull();
    }

    // ------------------------------------------------------------------
    // 推断失败绝不影响审计落库
    // ------------------------------------------------------------------

    @Test
    @DisplayName("id 访问器抛错 / longValue() 取值失败 -> 返回 null 而不是抛出")
    void accessorFailuresDegradeToNull() {
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(new ExplodingId(), null)))
                .as("访问器自身抛错：审计的字段推断不能把整条审计记录连累掉")
                .isNull();
        assertThat(AuditRefIdResolver.resolve(
                        method("createProject"), new Object[] {"{}"},
                        ApiResponse.ok(new ExplodingNumberEntity(), null)))
                .as("Number 形态合法但取值抛错：同样只能降级为 null")
                .isNull();
    }

    @Test
    @DisplayName("无方法签名（非方法连接点）-> 返回 null，不抛")
    void nonMethodSignatureIsRejected() {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(mock(Signature.class));

        assertThat(AuditRefIdResolver.resolve(pjp, null)).isNull();
    }

    @Test
    @DisplayName("有方法签名时从实参取路径变量（走 ProceedingJoinPoint 入口）")
    void resolvesThroughJoinPoint() {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method("updateAsset"));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[] {55L});

        assertThat(AuditRefIdResolver.resolve(pjp, null)).isEqualTo(55L);
    }

    @Test
    @DisplayName("实参个数少于形参（异常路径）-> 不越界，取不到的当没有")
    void shorterArgsArrayIsTolerated() {
        assertThat(AuditRefIdResolver.resolve(method("deleteZoneFloor"), new Object[] {1L}, null))
                .as("只给了一个实参时，第 2、3 个 id 视为不存在；不能因越界让审计整条失败")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("方法为 null / 实参为 null -> 不抛")
    void nullInputsAreTolerated() {
        assertThat(AuditRefIdResolver.resolve((Method) null, new Object[] {1L}, null)).isNull();
        assertThat(AuditRefIdResolver.resolve(method("updateAsset"), null, null)).isNull();
    }
}
