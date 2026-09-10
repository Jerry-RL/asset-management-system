import { useMemo } from 'react';
import { Select } from 'antd';
import { ApartmentOutlined, DownOutlined } from '@ant-design/icons';
import { ALL_COMPANIES_VALUE, useCompany, type CompanyOption } from '@/lib/company';

// ============================================================================
// 顶栏「全局公司切换」
//   切换后仅改变数据范围（X-Company-Id 请求头），不做路由跳转；
//   布局层按 scopeVersion 重挂载当前页面，各页面自然按新公司重新拉数据。
// ============================================================================

const ALL_COMPANIES_OPTION = { value: ALL_COMPANIES_VALUE, label: '全部公司' };

/** 公司层级深度：用于在选项里做缩进，体现「母公司 → 子公司」结构 */
const buildDepthMap = (companies: CompanyOption[]): Map<number, number> => {
  const byId = new Map(companies.map((c) => [c.id, c]));
  const depthCache = new Map<number, number>();
  const depthOf = (company: CompanyOption, guard: Set<number>): number => {
    const cached = depthCache.get(company.id);
    if (cached != null) return cached;
    // 脏数据（互为父子的环）时截断，避免无限递归
    if (company.parentId == null || guard.has(company.id)) return 0;
    const parent = byId.get(company.parentId);
    if (!parent) return 0;
    const next = new Set(guard).add(company.id);
    const depth = depthOf(parent, next) + 1;
    depthCache.set(company.id, depth);
    return depth;
  };
  const map = new Map<number, number>();
  for (const c of companies) map.set(c.id, depthOf(c, new Set<number>()));
  return map;
};

export function CompanySwitcher() {
  const { options, unrestricted, activeCompanyId, loading, setActiveCompany } = useCompany();

  const selectOptions = useMemo(() => {
    const depthMap = buildDepthMap(options);
    const items = options.map((c) => {
      const depth = depthMap.get(c.id) ?? 0;
      return {
        value: c.id,
        // 全角空格做层级缩进：保持可搜索标签干净（过滤用的是 label 原文）
        label: `${'　'.repeat(depth)}${c.name}`,
        title: c.name,
      };
    });
    return unrestricted ? [ALL_COMPANIES_OPTION, ...items] : items;
  }, [options, unrestricted]);

  // 只有一家可见公司且不受限时无需切换入口，避免顶栏出现无效控件
  if (!unrestricted && options.length <= 1) return null;

  return (
    <div className="flex items-center gap-1 min-w-0 max-w-[180px] sm:max-w-[240px]">
      <ApartmentOutlined className="text-gray-400 text-sm shrink-0" />
      <Select
        variant="borderless"
        size="small"
        className="min-w-0 flex-1"
        loading={loading}
        value={activeCompanyId ?? ALL_COMPANIES_VALUE}
        options={selectOptions}
        onChange={(v) => setActiveCompany(v === ALL_COMPANIES_VALUE ? null : Number(v))}
        showSearch
        optionFilterProp="title"
        suffixIcon={<DownOutlined className="text-[10px] text-gray-400" />}
        popupMatchSelectWidth={260}
        aria-label="切换公司"
        title="切换公司（影响全站数据范围）"
        notFoundContent="无可切换公司"
      />
    </div>
  );
}
