import { api } from '@/lib/api';

/** 组织结构公司（GET /org/companies） */
export interface CompanyOption {
  id: number;
  name: string;
  shortName?: string;
  parentId?: number | null;
  status?: number;
}

/** 组织架构公司下拉节点（TreeSelect） */
export interface CompanyTreeNode {
  value: number;
  title: string;
  /** 搜索匹配用：名称 + 简称 */
  key: string;
  /** 停用公司不可选，但已关联的历史数据仍能正常回显 */
  disabled?: boolean;
  children?: CompanyTreeNode[];
}

/** 兼容后端「直接返回数组」与「PageResult」两种列表形态 */
export const normalizeList = <T>(raw: unknown): T[] => {
  if (Array.isArray(raw)) return raw as T[];
  if (raw && typeof raw === 'object') {
    const obj = raw as { list?: T[]; records?: T[] };
    return obj.list ?? obj.records ?? [];
  }
  return [];
};

/**
 * 组织结构（母公司 → 子公司）→ TreeSelect 树。
 * 带环保护：脏数据造成的父子环不会导致无限递归。
 */
export const buildCompanyTree = (companies: CompanyOption[]): CompanyTreeNode[] => {
  const nodeOf = (c: CompanyOption): CompanyTreeNode => {
    const extra = c.shortName ? `（${c.shortName}）` : '';
    return {
      value: c.id,
      title: `${c.name}${extra}`,
      key: `${c.name}${extra}`,
      // 停用公司置灰不可选
      disabled: c.status === 0,
    };
  };
  const ids = new Set(companies.map((c) => c.id));
  const childrenOf = new Map<number, CompanyOption[]>();
  companies.forEach((c) => {
    if (c.parentId != null && ids.has(c.parentId)) {
      childrenOf.set(c.parentId, [...(childrenOf.get(c.parentId) ?? []), c]);
    }
  });
  const build = (c: CompanyOption, visited: Set<number>): CompanyTreeNode => {
    visited.add(c.id);
    const children = (childrenOf.get(c.id) ?? [])
      .filter((child) => !visited.has(child.id))
      .map((child) => build(child, new Set(visited)));
    const node = nodeOf(c);
    return children.length > 0 ? { ...node, children } : node;
  };
  return companies
    .filter((c) => c.parentId == null || !ids.has(c.parentId))
    .map((c) => build(c, new Set<number>()));
};

/** 拉取组织结构公司列表（失败时返回空数组，避免下拉整体不可用） */
export const loadCompanies = () =>
  api
    .get<unknown>('/org/companies')
    .then((raw) => normalizeList<CompanyOption>(raw))
    .catch(() => [] as CompanyOption[]);

/** 全部公司 id → 名称，用于列表回显 */
export const companyNameMap = (companies: CompanyOption[]) =>
  new Map(companies.map((c) => [c.id, c.name]));
