package com.ams.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.common.exception.AppException;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.service.CompanyTreeService;
import com.ams.modules.ownership.service.TransferDirectionResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内 / 外方向判定（设计 §5.2）。
 *
 * <p>公司树：
 * <pre>
 *   1 集团（根）
 *   ├── 2 城投
 *   │   └── 3 城投商运
 *   └── 4 文旅
 *   9 外部私企（parent_id 为空 → 自成一根）
 * </pre>
 *
 * <p>关键的三条：同根是内部、跨根是外部、**目标公司在树外（自成一根）也是外部**。
 * 最后一条是「外部受让方在组织架构建档、parent_id 留空」能成立的前提。
 *
 * <p>{@code isActiveCompany} 被显式桩住而不是让它走 {@code listCompanies}：真实实现内部确实调
 * {@code listCompanies}，但这里依赖的是「公司是否存在且启用」这个**语义**，直接桩语义比复刻
 * 一遍它的内部实现更不容易与生产漂移。
 */
class TransferDirectionResolverTest {

    /** 1 集团 / 2 城投 / 3 城投商运 / 4 文旅 / 9 外部私企。 */
    private static final Map<Long, Long> PARENT = Map.of(2L, 1L, 3L, 2L, 4L, 1L);

    private static final Set<Long> KNOWN = Set.of(1L, 2L, 3L, 4L, 9L);

    private CompanyTreeService companyTreeService;
    private TransferDirectionResolver resolver;

    @BeforeEach
    void setUp() {
        companyTreeService = mock(CompanyTreeService.class);

        List<Company> companies = new ArrayList<>();
        companies.add(company(1L, null, "集团"));
        companies.add(company(2L, 1L, "城投"));
        companies.add(company(3L, 2L, "城投商运"));
        companies.add(company(4L, 1L, "文旅"));
        companies.add(company(9L, null, "外部私企"));

        when(companyTreeService.listCompanies()).thenReturn(companies);
        when(companyTreeService.isActiveCompany(anyLong()))
                .thenAnswer(inv -> KNOWN.contains(inv.getArgument(0)));
        when(companyTreeService.ancestorIds(anyLong())).thenAnswer(inv -> {
            List<Long> path = new ArrayList<>();
            Long cursor = inv.getArgument(0);
            while (cursor != null && path.size() < KNOWN.size() + 1) {
                path.add(0, cursor);
                cursor = PARENT.get(cursor);
            }
            return path;
        });

        resolver = new TransferDirectionResolver(companyTreeService);
    }

    private static Company company(Long id, Long parentId, String name) {
        Company c = new Company();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        c.setStatus(1);
        return c;
    }

    @Test
    @DisplayName("兄弟公司之间：同根 → 内部流转")
    void siblingsAreInternal() {
        assertThat(resolver.resolve(2L, 4L)).isEqualTo("internal");
    }

    @Test
    @DisplayName("母公司与孙公司之间：同根 → 内部流转（方向无关）")
    void ancestorAndDescendantAreInternal() {
        assertThat(resolver.resolve(1L, 3L)).isEqualTo("internal");
        assertThat(resolver.resolve(3L, 1L)).isEqualTo("internal");
    }

    @Test
    @DisplayName("目标公司自成一根（外部受让方）→ 外部流转（方向无关）")
    void separateRootIsExternal() {
        assertThat(resolver.resolve(2L, 9L)).isEqualTo("external");
        assertThat(resolver.resolve(9L, 2L)).isEqualTo("external");
    }

    @Test
    @DisplayName("两棵外部树之间：也是外部流转（不能因为「都不是集团」就判成内部）")
    void twoForeignRootsAreExternal() {
        when(companyTreeService.listCompanies()).thenReturn(List.of(
                company(9L, null, "外部私企"), company(10L, null, "另一家外部")));
        when(companyTreeService.isActiveCompany(anyLong()))
                .thenAnswer(inv -> Set.of(9L, 10L).contains(inv.getArgument(0)));
        when(companyTreeService.ancestorIds(anyLong())).thenAnswer(inv -> {
            // 显式取到 Long 局部变量：直接写 List.of(inv.getArgument(0)) 会让编译器把
            // 泛型 T 推成 Long[]（varargs 形参），运行时 ClassCastException
            Long id = inv.getArgument(0);
            return List.of(id);
        });

        assertThat(resolver.resolve(9L, 10L)).isEqualTo("external");
    }

    @Test
    @DisplayName("公司不存在时拒绝：ancestorIds 会把不存在的公司当成自己的根，静默算成「外部」")
    void rejectsUnknownCompany() {
        assertThatThrownBy(() -> resolver.resolve(2L, 999L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("新公司不存在或已停用");
    }

    @Test
    @DisplayName("三处 ID 为 null 时拒绝，而不是静默按「外部」处理")
    void rejectsNullCompany() {
        assertThatThrownBy(() -> resolver.resolve(null, 9L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("均必填");
        assertThatThrownBy(() -> resolver.resolve(2L, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("均必填");
    }

    @Test
    @DisplayName("direction 与公司树不一致时 400：声明内部但目标在树外")
    void rejectsDirectionMismatch() {
        assertThatThrownBy(() -> resolver.assertDirectionMatches("internal", 2L, 9L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("外部流转");
    }

    @Test
    @DisplayName("direction 与公司树不一致时 400：声明外部但目标在集团内")
    void rejectsExternalClaimForInternalTarget() {
        assertThatThrownBy(() -> resolver.assertDirectionMatches("external", 2L, 3L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("内部流转");
    }

    @Test
    @DisplayName("direction 与公司树一致时放行")
    void acceptsMatchingDirection() {
        resolver.assertDirectionMatches("internal", 2L, 3L);
        resolver.assertDirectionMatches("external", 2L, 9L);
    }

    @Test
    @DisplayName("公司名映射一次查全：列表页每行要显示「A → B」，逐行查会变成 N+1")
    void namesByIdCoversAllCompanies() {
        Map<Long, String> names = resolver.namesById();

        assertThat(names).isInstanceOf(LinkedHashMap.class).hasSize(5);
        assertThat(names).containsEntry(1L, "集团").containsEntry(9L, "外部私企");
    }
}
