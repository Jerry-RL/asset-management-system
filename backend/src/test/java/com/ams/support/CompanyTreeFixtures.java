package com.ams.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ams.modules.org.entity.Company;
import com.ams.modules.org.mapper.CompanyMapper;
import com.ams.modules.org.service.CompanyTreeService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 公司树测试夹具（设计 5.2 的数据范围判定需要一棵真实的树）。
 *
 * <h2>为什么桩的是 Mapper 而不是 CompanyTreeService</h2>
 * 只桩住 {@link CompanyMapper}、让**真实的** {@link CompanyTreeService} 参与断言。
 * 若直接 mock {@code descendantIds()}，那「排除母公司连带排除子公司」这类断言就变成同义反复
 * （mock 说返回什么就断言什么），恰好放过了最容易写错的子树遍历。
 *
 * <h2>树形（与演示环境一致，便于与手测结果对照）</h2>
 * <pre>
 * 1 淮安市国资集团（根）
 * ├─ 2 淮安城投资产管理有限公司            ← 多数账号的「本司」
 * │  ├─ 3 淮安城投商业运营有限公司
 * │  ├─ 4 淮安城投物业服务有限公司
 * │  └─ 9 淮安城投（已划转）公司  status=0  ← 停用公司，见 {@link #DISABLED} 的说明
 * ├─ 5 淮安文旅发展集团有限公司            ← 常被整棵排除
 * │  └─ 6 楚州古城文旅运营有限公司
 * └─ 7 淮安水务集团有限公司                ← 与 2 同级、无隶属（「互不可见」的对方）
 * 8 江苏交通控股集团（独立的另一个根）        ← 与 1 无隶属，用于测「完全无关的树不可见」
 * </pre>
 *
 * <p>这棵树刻意覆盖了数据范围的四种关系：**上级**（1 对 2）、**下级子树**（2 对 3/4/9）、
 * **同级无隶属**（2 与 7）、**跨树无关**（8）。
 */
public final class CompanyTreeFixtures {

    /** 集团（根）：作为「A 是 B 的上级」中的 A。 */
    public static final long GROUP = 1L;
    /** 城投资管：多数账号的所属公司。 */
    public static final long SUB = 2L;
    /** 商业运营：SUB 的下级。 */
    public static final long COMMERCIAL = 3L;
    /** 物业服务：SUB 的下级。 */
    public static final long PROPERTY = 4L;
    /** 文旅集团：常被整棵排除的子树根（其下 {@link #ANCIENT} 用于验证「继承排除」）。 */
    public static final long CULTURE = 5L;
    /** 古城文旅：CULTURE 的下级，与 CULTURE 一起构成 D15 的「上级排除 + 子级也显式排除」场景。 */
    public static final long ANCIENT = 6L;
    /** 水务集团：与 SUB 同级、无隶属关系。 */
    public static final long WATER = 7L;
    /** 独立的另一个根：与 GROUP 无任何隶属关系。 */
    public static final long OTHER_ROOT = 8L;
    /** 已停用公司（status=0），挂在 SUB 之下。 */
    public static final long DISABLED = 9L;

    private final List<Company> companies;
    private final CompanyTreeService treeService;

    private CompanyTreeFixtures(List<Company> companies, CompanyTreeService treeService) {
        this.companies = companies;
        this.treeService = treeService;
    }

    /** 标准树 + 真实 {@link CompanyTreeService}（其 CompanyMapper 已桩住）。 */
    public static CompanyTreeFixtures standard() {
        List<Company> companies = List.of(
                company(GROUP, null, "淮安市国资集团", "国资集团", 1),
                company(SUB, GROUP, "淮安城投资产管理有限公司", "城投资管", 1),
                company(COMMERCIAL, SUB, "淮安城投商业运营有限公司", "城投商运", 1),
                company(PROPERTY, SUB, "淮安城投物业服务有限公司", "城投物业", 1),
                company(DISABLED, SUB, "淮安城投（已划转）公司", "已划转", 0),
                company(CULTURE, GROUP, "淮安文旅发展集团有限公司", "文旅集团", 1),
                company(ANCIENT, CULTURE, "楚州古城文旅运营有限公司", "古城文旅", 1),
                company(WATER, GROUP, "淮安水务集团有限公司", "水务集团", 1),
                company(OTHER_ROOT, null, "江苏交通控股集团", "江苏交控", 1));

        CompanyMapper companyMapper = mock(CompanyMapper.class);
        // listCompanies() 是树遍历的唯一数据源（listCompaniesTreeOrdered / descendantIds /
        // isActiveCompany / allCompanyIds 都经由它），因此桩住它即可驱动全部真实逻辑
        // 真实查询带 ORDER BY sort, id：这里按同样顺序返回，否则 listCompaniesTreeOrdered
        // 的「父在子前」会被夹具自己的入参顺序掩盖
        List<Company> ordered = companies.stream()
                .sorted(Comparator.<Company, Integer>comparing(Company::getSort)
                        .thenComparing(Company::getId))
                .toList();
        when(companyMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(ordered));
        // hasChildren() 用 selectCount：返回 0 而非 null，避免调用方被 null 拆箱
        when(companyMapper.selectCount(any())).thenReturn(0L);

        return new CompanyTreeFixtures(companies, new CompanyTreeService(companyMapper));
    }

    public static Company company(long id, Long parentId, String name, String shortName, int status) {
        Company c = new Company();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        c.setShortName(shortName);
        c.setCompanyType(parentId == null ? "group" : "subsidiary");
        // sort 与 id 对齐，保证 listCompanies() 的排序稳定可预期
        c.setSort((int) id);
        c.setStatus(status);
        return c;
    }

    public List<Company> companies() {
        return companies;
    }

    /** 真实实现的公司树服务（枚举用）。 */
    public CompanyTreeService treeService() {
        return treeService;
    }

    public Company company(long id) {
        return companies.stream()
                .filter(c -> c.getId() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("夹具中没有公司 " + id));
    }

    /** 子树（含自身），直接走真实遍历，供断言里做对照。 */
    public Set<Long> subtree(long id) {
        return treeService.descendantIds(id);
    }
}
