import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, getActiveCompanyId, setActiveCompanyStorage } from '@/lib/api';
import { useAuth } from '@/lib/auth';

// ============================================================================
// 全局公司切换（顶栏公司下拉）
//   可切换范围由后端按登录用户的角色数据范围计算：
//     - super_admin / 数据范围 all：全部启用公司，默认「全部公司」（不限制）
//     - 其余账号：所属公司 + 其下级公司子树，默认所属公司
//   切换只改前端状态：api 层把选中公司放进 X-Company-Id 请求头，
//   后端过滤器校验后写入「当前生效公司」，所有列表的数据范围随之收敛。
// ============================================================================

export interface CompanyOption {
  id: number;
  name: string;
  shortName?: string | null;
  parentId?: number | null;
}

interface CompanyScopeResponse {
  companies: CompanyOption[];
  homeCompanyId: number | null;
  activeCompanyId: number | null;
  unrestricted: boolean;
  scoped: boolean;
}

interface CompanyState {
  /** 可切换公司（按公司树顺序） */
  options: CompanyOption[];
  /** 数据范围不受公司限制（super_admin / all），此时展示「全部公司」选项 */
  unrestricted: boolean;
  /** 当前生效公司 ID；null 表示全部公司 */
  activeCompanyId: number | null;
  /** 当前生效公司名称，用于顶栏展示 */
  activeCompanyName: string;
  loading: boolean;
  /**
   * 数据范围版本号：每次切换公司自增。
   * 布局层用它做 key 重挂载当前路由，使各页面重新拉取数据（列表/统计/地图等）。
   */
  scopeVersion: number;
  setActiveCompany: (companyId: number | null) => void;
}

/** 「全部公司」选项的哨兵值：antd Select 不接受 undefined 作为受控值 */
export const ALL_COMPANIES_VALUE = 0;
const ALL_COMPANIES_LABEL = '全部公司';

const CompanyContext = createContext<CompanyState>({
  options: [],
  unrestricted: false,
  activeCompanyId: null,
  activeCompanyName: ALL_COMPANIES_LABEL,
  loading: false,
  scopeVersion: 0,
  setActiveCompany: () => {},
});

const nameOf = (options: CompanyOption[], id: number | null): string => {
  if (id == null) return ALL_COMPANIES_LABEL;
  return options.find((c) => c.id === id)?.name ?? ALL_COMPANIES_LABEL;
};

export function CompanyProvider({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  const [options, setOptions] = useState<CompanyOption[]>([]);
  const [unrestricted, setUnrestricted] = useState(false);
  const [activeCompanyId, setActiveCompanyId] = useState<number | null>(() =>
    getActiveCompanyId(),
  );
  const [loading, setLoading] = useState(false);
  const [scopeVersion, setScopeVersion] = useState(0);

  /**
   * 拉取可切换公司，并把「本地已存的公司」与服务端可选项对齐：
   * 本地值失效（越权 / 公司已删 / 跨账号残留）时回落为服务端给的默认值，
   * 避免请求头带着一个无效公司 ID 到处跑。
   */
  const load = useCallback(async () => {
    if (!token) {
      setOptions([]);
      setUnrestricted(false);
      setActiveCompanyId(null);
      return;
    }
    setLoading(true);
    try {
      const data = await api.get<CompanyScopeResponse>('/auth/companies');
      setOptions(data.companies ?? []);
      setUnrestricted(!!data.unrestricted);
      const stored = getActiveCompanyId();
      const valid =
        stored != null && (data.companies ?? []).some((c) => c.id === stored);
      const resolved = valid ? stored : (data.activeCompanyId ?? null);
      setActiveCompanyId(resolved);
      persist(resolved, data.companies ?? []);
    } catch {
      // 接口异常时保留本地值，避免顶栏闪烁；鉴权失败由 api 层统一跳登录
      setOptions([]);
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleChange = useCallback(
    (companyId: number | null) => {
      setActiveCompanyId(companyId);
      persist(companyId, options);
      // 版本自增 → 布局层重挂载当前页面 → 各页面按新公司重新拉数据
      setScopeVersion((v) => v + 1);
    },
    [options],
  );

  const value = useMemo<CompanyState>(
    () => ({
      options,
      unrestricted,
      activeCompanyId,
      activeCompanyName: nameOf(options, activeCompanyId),
      loading,
      scopeVersion,
      setActiveCompany: handleChange,
    }),
    [options, unrestricted, activeCompanyId, loading, scopeVersion, handleChange],
  );

  return <CompanyContext.Provider value={value}>{children}</CompanyContext.Provider>;
}

/** 写入本地：公司 ID 供 api 层拼请求头，名称仅用于顶栏回显 */
function persist(companyId: number | null, options: CompanyOption[]) {
  setActiveCompanyStorage(companyId, nameOf(options, companyId));
}

export function useCompany() {
  return useContext(CompanyContext);
}
