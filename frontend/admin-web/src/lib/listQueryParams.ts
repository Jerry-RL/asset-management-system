/**
 * 列表 URL 查询参数的**纯逻辑**（设计 §5.1 / §5.2）。
 *
 * <p>刻意不 import React / react-router：URL 读写的语义（正整数兜底、缺省值不写、
 * 合并式写入、筛选整体替换）是本设计里最容易写歪、也最容易回归的部分，而组件层在本仓
 * 无法被自动断言（无 vitest）。抽成零依赖纯模块后可直接跑
 * `node scripts/test-list-query-params.mjs`（Node ≥ 22.6 的类型剥离）。
 *
 * <p>**只解释自己声明的键**：`filterKeys` 之外的 query 参数原样保留、不解释，避免外部
 * 深链参数被误当列表筛选拼进接口（设计 §8）。
 *
 * <p>**前缀采用驼峰拼接**（设计 §4 命名约定）：`prefix: 'asset'` 对应
 * `assetPage` / `assetPageSize` / `assetKeyword` / `asset<筛选键>`（筛选键首字母同样大写），
 * 而不是平铺的 `assetpage`。这样 `ProjectListPane` 的 `projectPage` /
 * `projectKeyword` 与 `ZoneAssetPane` 的 `assetPage` 才与设计表一致。
 */

/** 单个列表负责的参数集合（键名相对 `prefix`） */
export interface ListQueryOptions {
  /** 参数前缀：一页里有多套列表时用（「项目分区管理」左栏 project / 右栏 asset） */
  prefix?: string;
  /** 缺省每页条数 */
  defaultPageSize?: number;
  /** 本列表负责的筛选键（不含 prefix） */
  filterKeys?: string[];
}

/** 列表状态 */
export interface ListQueryState {
  page: number;
  pageSize: number;
  keyword: string;
  filters: Record<string, string>;
}

/** 写入时的局部更新：只写出现过的键 */
export interface ListQueryPatch {
  page?: number;
  pageSize?: number;
  keyword?: string;
  filters?: Record<string, string>;
}

/**
 * 保留字：筛选键不得与列表自身的分页/关键字键重名，否则会互相覆盖（设计 §4）。
 * 与 {@link findReservedFilterKeys} 同源，供开发态告警与断言使用。
 */
export const RESERVED_LIST_KEYS = ['page', 'pageSize', 'keyword'] as const;

/** 返回撞保留字的筛选键（纯函数，供开发态告警与测试断言） */
export const findReservedFilterKeys = (filterKeys: string[]): string[] =>
  filterKeys.filter((key) => (RESERVED_LIST_KEYS as readonly string[]).includes(key));

/**
 * 把「本列表内的相对键」拼成 URL 键名（设计 §4）。
 *
 * <p>无前缀时原样（`page` / `status`）；有前缀时前缀 + 首字母大写（`assetPage`、
 * `projectKeyword`）。前缀必须让两套列表在同一 URL 上互不覆盖，而驼峰命名与设计表
 * 里登记的 `assetPage` / `projectPage` / `projectKeyword` 保持一致。
 */
const buildKey = (prefix: string, key: string): string =>
  prefix ? `${prefix}${key.charAt(0).toUpperCase()}${key.slice(1)}` : key;

/**
 * 正整数解析：缺失 / 非纯数字 / 非正数一律回落到 `fallback`。
 *
 * <p>用正则而不是 `Number.isFinite`：后者会把 `'1.5'` / `'1e3'` / `'0x10'` 都当成合法
 * 数字，拼出 `page=1.5` 这类请求（后端 400，页面进错误态）。与「项目分区管理」的
 * `toPositiveInt` 同口径。
 */
export const readPositiveInt = (raw: string | null, fallback: number): number => {
  if (!raw || !/^\d+$/.test(raw)) return fallback;
  const value = Number(raw);
  return value > 0 ? value : fallback;
};

/** 从 URL 参数读出列表状态（缺省：第 1 页 / defaultPageSize / 空关键字 / 无筛选） */
export const readListQuery = (
  params: URLSearchParams,
  options: ListQueryOptions = {},
): ListQueryState => {
  const { prefix = '', defaultPageSize = 10, filterKeys = [] } = options;

  const filters: Record<string, string> = {};
  filterKeys.forEach((key) => {
    const value = params.get(buildKey(prefix, key));
    // 只收非空值：`?status=` 与「没有这个参数」等价
    if (value) filters[key] = value;
  });

  return {
    page: readPositiveInt(params.get(buildKey(prefix, 'page')), 1),
    pageSize: readPositiveInt(params.get(buildKey(prefix, 'pageSize')), defaultPageSize),
    keyword: params.get(buildKey(prefix, 'keyword')) ?? '',
    filters,
  };
};

/**
 * 计算写回后的参数：**入参 `params` 不变**，返回新的 `URLSearchParams`。
 *
 * <p>合并式写入 —— 只动本列表负责的键，其余参数原样保留。「项目分区管理」一页里左右
 * 两套列表共用同一个 URL，整包替换会把对方的分页/关键字抹掉。
 *
 * <p>缺省值不写进 URL（设计 §8）：`page=1`、`pageSize === defaultPageSize`、空
 * `keyword`、空筛选值一律删除，避免 `?page=1&keyword=` 这类噪声。
 *
 * <p>`patch.filters` 视为本列表筛选的**完整目标状态**：未出现在其中的已声明筛选键会被
 * 清空。调用方用 `{ ...filters, [key]: value }` 维护完整集合（`setFilter` 已如此实现）。
 */
export const applyListPatch = (
  params: URLSearchParams,
  patch: ListQueryPatch,
  options: ListQueryOptions & { resetPage?: boolean } = {},
): URLSearchParams => {
  const { prefix = '', defaultPageSize = 10, filterKeys = [], resetPage = false } = options;
  const next = new URLSearchParams(params);

  /** 写一个键：空值删除，避免留下 `?keyword=` 这种噪声 */
  const write = (key: string, value: string | number | undefined) => {
    const name = buildKey(prefix, key);
    if (value == null || value === '') next.delete(name);
    else next.set(name, String(value));
  };

  if (patch.page !== undefined) {
    if (patch.page === 1) next.delete(buildKey(prefix, 'page'));
    else write('page', patch.page);
  }
  if (patch.pageSize !== undefined) {
    if (patch.pageSize === defaultPageSize) next.delete(buildKey(prefix, 'pageSize'));
    else write('pageSize', patch.pageSize);
  }
  if (patch.keyword !== undefined) write('keyword', patch.keyword);

  if (patch.filters) {
    // 只写回声明的筛选键：未声明的键不属于本列表，不得因为「patch 里没有」而被删掉
    filterKeys.forEach((key) => write(key, patch.filters?.[key]));
  }

  // 显式给了 page 就以它为准；否则 resetPage 表示「回到第 1 页」= 删掉 page 参数
  if (resetPage && patch.page === undefined) next.delete(buildKey(prefix, 'page'));

  return next;
};
