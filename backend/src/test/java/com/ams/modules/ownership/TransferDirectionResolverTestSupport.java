package com.ams.modules.ownership;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.modules.org.entity.Company;
import com.ams.modules.org.service.CompanyTreeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公司树测试夹具：1 集团（根）→ 2 城投 → 3 城投商运；1 → 4 文旅；9 外部私企（自成一根）。
 *
 * <p>抽出来是为了让「方向判定」与「草稿校验 / 生效」两个测试类用**同一棵**树 —— 各自搭一棵
 * 会让「同根 / 跨根」的语义在两个文件里各说一遍，改一处忘一处。
 *
 * <p>{@code isActiveCompany} 被显式桩住而不是让它走 {@code listCompanies}：真实实现内部确实调
 * {@code listCompanies}，但这里依赖的是「公司是否存在且启用」这个**语义**，
 * 直接桩语义比复刻一遍它的内部实现更不容易与生产漂移。
 */
public final class TransferDirectionResolverTestSupport {

    /** 集团（根公司）。 */
    public static final long GROUP = 1L;

    /** 城投（原公司常用值）。 */
    public static final long CHENGTOU = 2L;

    /** 城投商运（同集团的另一家公司，用于「内部流转」）。 */
    public static final long CHENGTOU_OPERATION = 3L;

    /** 文旅（同集团的兄弟公司）。 */
    public static final long CULTURE_TOURISM = 4L;

    /** 外部私企（{@code parent_id} 为空，自成一根，用于「外部流转」）。 */
    public static final long OUTSIDE = 9L;

    /** 子 → 父；{@code 1} 与 {@code 9} 无父（各自成根）。 */
    private static final Map<Long, Long> PARENT = Map.of(
            CHENGTOU, 1L,
            CHENGTOU_OPERATION, CHENGTOU,
            CULTURE_TOURISM, 1L);

    private static final Set<Long> KNOWN = Set.of(1L, CHENGTOU, CHENGTOU_OPERATION, CULTURE_TOURISM, OUTSIDE);

    private TransferDirectionResolverTestSupport() {
    }

    /** 一棵建好桩的公司树：{@code listCompanies} / {@code ancestorIds} / {@code isActiveCompany} 全部可用。 */
    public static CompanyTreeService tree() {
        CompanyTreeService service = mock(CompanyTreeService.class);
        List<Company> companies = new ArrayList<>();
        companies.add(company(1L, null, "集团"));
        companies.add(company(CHENGTOU, 1L, "城投"));
        companies.add(company(CHENGTOU_OPERATION, CHENGTOU, "城投商运"));
        companies.add(company(CULTURE_TOURISM, 1L, "文旅"));
        companies.add(company(OUTSIDE, null, "外部私企"));
        when(service.listCompanies()).thenReturn(companies);
        when(service.isActiveCompany(anyLong()))
                .thenAnswer(inv -> KNOWN.contains(inv.getArgument(0)));
        when(service.ancestorIds(anyLong())).thenAnswer(inv -> {
            List<Long> path = new ArrayList<>();
            // 先落到 Long 局部变量：直接写 List.of(inv.getArgument(0)) 会让编译器把泛型 T
            // 推成 Long[]（varargs 形参），运行时报 ClassCastException
            Long cursor = inv.getArgument(0);
            while (cursor != null && path.size() <= KNOWN.size()) {
                path.add(0, cursor);
                cursor = PARENT.get(cursor);
            }
            return path;
        });
        return service;
    }

    public static Company company(Long id, Long parentId, String name) {
        Company c = new Company();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        c.setStatus(1);
        return c;
    }
}
