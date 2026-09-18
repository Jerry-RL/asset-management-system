import { api } from '@/lib/api';
import { normalizeList } from '@/lib/org';

/**
 * 资产运营人员管理（与后端 `com.ams.modules.assetoperator` 逐字段对应，V60）。
 *
 * <p>三个概念在本模块里是**平级**的：人员、角色、资产运营范围。范围是「项目 / 分区 / 资产」
 * 三选一，但**可以混选多条**，因此它是数组而不是 `targetType + targetId` 的单一标的形式
 * （与抵押记录的差异就在这里，不要照抄那边的单标的模型）。
 *
 * <p>`scopeType` 用字面量联合类型而不是 `string`：三种取值决定候选查询与展示拼接，
 * 写成 `string` 会让「少写一个 case」变成静默的空白单元格。
 */

export type OperatorScopeType = 'project' | 'zone' | 'asset';

/** 与后端 `AssetOperatorScopeView` 对应 */
export interface OperatorScope {
  scopeType: OperatorScopeType | string;
  scopeId: number;
  /** 标的名；后端在标的已被删除时回「已删除标的 #id」而不是 null */
  scopeName?: string | null;
  /** 分区 / 资产所属项目名；项目为 null（它自己就是顶层） */
  parentName?: string | null;
}

/** 与后端 `AssetOperatorView` 对应 */
export interface OperatorView {
  id: number;
  userId: number;
  userName?: string | null;
  phone?: string | null;
  companyId?: number | null;
  companyName?: string | null;
  departmentId?: number | null;
  departmentName?: string | null;
  roleIds: number[];
  roleNames: string[];
  /** 仅详情返回（列表为 null）：列表每行把全部范围名拉出来会让一页塞进上百个对象 */
  scopes?: OperatorScope[] | null;
  projectCount: number;
  zoneCount: number;
  assetCount: number;
  status: number;
  remark?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** 与后端 `AssetOperatorUserOption` 对应 */
export interface OperatorUserOption {
  userId: number;
  name?: string | null;
  phone?: string | null;
  companyId?: number | null;
  companyName?: string | null;
  departmentId?: number | null;
  departmentName?: string | null;
}

/** 与后端 `AssetOperatorRoleOption` 对应 */
export interface OperatorRoleOption {
  roleId: number;
  code?: string | null;
  name: string;
  dataScope?: string | null;
}

/** 与后端 `AssetOperatorScopeOption` 对应 */
export interface OperatorScopeOption {
  scopeId: number;
  scopeType: OperatorScopeType | string;
  name?: string | null;
  parentName?: string | null;
  assetNo?: string | null;
}

/** 表单提交体（与后端 `AssetOperatorInput` 对应） */
export interface OperatorInput {
  userId: number;
  roleIds: number[];
  scopes: { scopeType: string; scopeId: number }[];
  status?: number;
  remark?: string | null;
}

export interface OperatorListParams {
  page: number;
  pageSize: number;
  keyword?: string;
  status?: number;
}

const toQuery = (params: object): string => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
};

/**
 * 兼容「直接数组」与「PageResult」两种响应形态。
 *
 * 与 `ResourcePage` / `lib/org.ts` 同口径的兜底：后端某天改成返回数组时，
 * 页面会显示「0 条」而不是抛错 —— 后者会让整页白屏，前者至少能自查。
 */
const readPage = <T>(raw: unknown): { list: T[]; total: number } => {
  const list = normalizeList<T>(raw);
  const total =
    raw && typeof raw === 'object' && typeof (raw as { total?: number }).total === 'number'
      ? (raw as { total: number }).total
      : list.length;
  return { list, total };
};

export const assetOperatorApi = {
  list: async (params: OperatorListParams) => {
    const raw = await api.get<unknown>(`/asset-operators?${toQuery(params)}`);
    return readPage<OperatorView>(raw);
  },

  /** 详情：比列表多返回 `scopes`（含标的名） */
  detail: (id: number) => api.get<OperatorView>(`/asset-operators/${id}`),

  /**
   * 人员下拉。
   *
   * <p>刻意不复用 `/system/users`：那个端点要求 `org.user:view`，
   * 维护运营人员档案的人未必持有人员维护权限 —— 缺它就选不到人，功能等于不存在。
   */
  userOptions: async (params: {
    keyword?: string;
    companyId?: number;
    departmentId?: number;
    page?: number;
    pageSize?: number;
  }) => {
    const raw = await api.get<unknown>(`/asset-operators/user-options?${toQuery(params)}`);
    return readPage<OperatorUserOption>(raw);
  },

  /** 角色下拉：后端只返回已启用角色（复用 `/system/roles` 会要求 `system.role:view`） */
  roleOptions: async () => {
    const raw = await api.get<unknown>('/asset-operators/role-options');
    return normalizeList<OperatorRoleOption>(raw);
  },

  /**
   * 运营范围下拉（项目 / 分区 / 资产）。
   *
   * <p>`companyId` 必填：后端按公司收敛候选，缺失直接 400。本项目里运营范围的意义就是
   * 「在哪个公司下负责什么」，没有公司就没有候选可言。
   */
  scopeOptions: async (params: {
    scopeType: OperatorScopeType | string;
    companyId: number;
    projectId?: number;
    keyword?: string;
    page?: number;
    pageSize?: number;
  }) => {
    const raw = await api.get<unknown>(`/asset-operators/scope-options?${toQuery(params)}`);
    return readPage<OperatorScopeOption>(raw);
  },

  create: (input: OperatorInput) => api.post<OperatorView>('/asset-operators', input),

  update: (id: number, input: OperatorInput) =>
    api.put<OperatorView>(`/asset-operators/${id}`, input),

  updateStatus: (id: number, status: number) =>
    api.put<OperatorView>(`/asset-operators/${id}/status`, { status }),

  /** 注意是 `api.del`：`api` 上没有 `delete` 方法（`delete` 是 JS 关键字，仓内统一用 `del`）。 */
  remove: (id: number) => api.del<void>(`/asset-operators/${id}`),
};

/** 范围类型选项（与后端 `AssetOperatorScope.TYPE_*` 三取值一一对应）。 */
export const SCOPE_TYPE_OPTIONS: { value: OperatorScopeType; label: string }[] = [
  { value: 'project', label: '项目' },
  { value: 'zone', label: '分区' },
  { value: 'asset', label: '资产' },
];

const SCOPE_TYPE_LABELS: Record<string, string> = {
  project: '项目',
  zone: '分区',
  asset: '资产',
};

export const scopeTypeLabel = (scopeType?: string | null): string =>
  SCOPE_TYPE_LABELS[String(scopeType)] ?? String(scopeType ?? '-');

/** 状态选项（与后端 `AssetOperator.STATUS_*` 对应）。 */
export const OPERATOR_STATUS: Record<string, string> = {
  '1': '启用',
  '0': '停用',
};

export const OPERATOR_STATUS_OPTIONS = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

/**
 * 范围展示文案：`上级 / 标的`。
 *
 * <p>前端是**唯一拼接点**（后端只回原料 `scopeName` / `parentName`）——
 * 与 `mortgageTargetLabel` 同口径：拼接规则散在多处，两个页面显示成不同名字是迟早的事。
 */
export const scopeLabel = (scope: OperatorScope): string => {
  const name = scope.scopeName ?? `#${scope.scopeId}`;
  return scope.parentName ? `${scope.parentName} / ${name}` : name;
};

/** 候选（下拉）文案：资产带编号，便于区分同名资产。 */
export const scopeOptionLabel = (option: OperatorScopeOption): string => {
  const name = option.assetNo ? `${option.name}（${option.assetNo}）` : String(option.name ?? '');
  return option.parentName ? `${option.parentName} / ${name}` : name;
};

/** 「项目 n / 分区 n / 资产 n」的范围计数摘要（列表列用）。 */
export const scopeCountSummary = (row: OperatorView): string =>
  `项目 ${row.projectCount} / 分区 ${row.zoneCount} / 资产 ${row.assetCount}`;
