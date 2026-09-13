package com.ams.modules.ownership.service;

import com.ams.common.exception.AppException;
import com.ams.common.exception.ErrorCode;
import com.ams.modules.org.entity.Company;
import com.ams.modules.org.service.CompanyTreeService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 权属流转的内 / 外方向判定（设计 §5.2）—— 本模块**唯一**的「内 / 外」真相点。
 *
 * <p>规则：原公司与目标公司沿 {@code company.parent_id} 上溯到根，<b>同根 = 内部流转，
 * 不同根 = 外部流转</b>。
 *
 * <p>为什么不用 {@code CompanyTreeService.rootCompany()}：它取 {@code parent_id IS NULL} 中
 * {@code sort, id} 最小的那一行。一旦有第二棵树（外部受让方），就可能取到外部公司，
 * 「内部」的范围会随外部公司的排序变化而漂移。
 *
 * <p>为什么不用 {@code company_type} 字典：V22 定义的值是 {@code provincial_sasac} /
 * {@code public_institution} / {@code state_owned} / {@code private_enterprise}，而演示种子
 * 写的是 {@code group} / {@code subsidiary}，两边本就不一致，不能作为判定依据。
 */
@Service
public class TransferDirectionResolver {

    public static final String INTERNAL = "internal";
    public static final String EXTERNAL = "external";

    private final CompanyTreeService companyTreeService;

    public TransferDirectionResolver(CompanyTreeService companyTreeService) {
        this.companyTreeService = companyTreeService;
    }

    /**
     * 目标公司与原公司是否同根。
     *
     * <p>两个公司都必须存在且启用：{@code ancestorIds} 对不存在的公司会把它自己当成根
     * （父指针查不到就停），于是任何脏 id 都会被静默算成「外部」—— 必须先挡掉。
     */
    public String resolve(Long fromCompanyId, Long toCompanyId) {
        if (fromCompanyId == null || toCompanyId == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "原公司与新公司均必填");
        }
        requireActiveCompany(fromCompanyId, "原公司");
        requireActiveCompany(toCompanyId, "新公司");
        return rootOf(fromCompanyId).equals(rootOf(toCompanyId)) ? INTERNAL : EXTERNAL;
    }

    /**
     * 校验调用方声明的 {@code direction} 与公司树判定一致。
     *
     * <p>「方向」是用户选的字典值，不是由树反推出来的 —— 二者不一致说明选择与事实矛盾
     * （例如声明内部流转却填了一家集团外的公司），必须拒绝而不是以树覆盖用户选择：
     * 静默纠正会让界面上显示的方向与落库的方向不一致。
     */
    public void assertDirectionMatches(String direction, Long fromCompanyId, Long toCompanyId) {
        String actual = resolve(fromCompanyId, toCompanyId);
        if (!actual.equals(direction)) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    EXTERNAL.equals(actual)
                            ? "所选新公司不在本集团内，请改选「外部流转」"
                            : "所选新公司属于本集团，请改选「内部流转」");
        }
    }

    /**
     * 公司名一次查全（列表页每行都要显示「A → B」，逐行查会变成 N+1）。
     *
     * <p>放在本类而不是让各调用方自己注入 {@code CompanyTreeService}：公司树的读取口径
     * （含停用公司在内、按 sort/id 排序）只应该有这一个入口，否则「谁能看到哪家公司」
     * 会随调用方而变。
     */
    public Map<Long, String> namesById() {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Company c : companyTreeService.listCompanies()) {
            names.put(c.getId(), c.getName());
        }
        return names;
    }

    /** 上溯到根（{@link CompanyTreeService#ancestorIds} 返回 root → 自身，故取首元素）。 */
    private Long rootOf(Long companyId) {
        List<Long> path = companyTreeService.ancestorIds(companyId);
        return path.isEmpty() ? companyId : path.get(0);
    }

    private void requireActiveCompany(Long companyId, String label) {
        if (!companyTreeService.isActiveCompany(companyId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, label + "不存在或已停用：" + companyId);
        }
    }
}
