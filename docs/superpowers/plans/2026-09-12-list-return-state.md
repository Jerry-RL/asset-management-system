# 列表「返回还原」实现计划（URL 作为分页/筛选的唯一真相）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 admin-web 的列表页把「页码 / 每页条数 / 关键字 / 筛选 / 视图」存进 URL，使「跳详情或编辑 → 返回」以及浏览器前进后退、刷新都能还原到跳转前那一次的分页与筛选。

**Architecture:** 新增一个零依赖纯函数模块（`src/lib/listQueryParams.ts`，URL 参数的读/写语义）与一个薄 React 封装（`src/lib/listQuery.ts`，`useListQuery` / `useUrlParam`）。各列表页删掉本地的 `useState` 分页筛选状态、改为读写 URL，并把「改 state + 显式调 `load()`」改为「`load` 依赖 URL 派生值 + `useEffect` 拉取」。跳转来源继续用既有的 `currentPath(location)` / `useBackNavigate`，此时 `currentPath` 会自然带上 query。

**Tech Stack:** React 18 + TypeScript + Vite + antd 6 + react-router-dom 6.30；测试用 Node 原生类型剥离（Node ≥ 22.6，本机 24），**不引入任何新依赖**。

**关联设计：** `docs/superpowers/specs/2026-09-12-list-return-state-design.md`

## Global Constraints

- **只改 `frontend/admin-web`**，不动 `backend`、`h5-tenant`、`h5-worker`、两个小程序。
- **保留字**：`page`、`pageSize`、`keyword` 不得被业务筛选键占用（`listQueryParams.ts` 提供 `findReservedFilterKeys` 供开发态告警）。
- **写入一律 `{ replace: true }`**：翻页/筛选不塞满浏览器历史（与 `ProjectZonesPage` §5.4 既有口径一致）。
- **写入一律合并式**：只改本列表负责的键，其余 URL 参数原样保留 —— 「项目分区管理」一页里左右两套列表共用同一个 URL。
- **缺省值不写进 URL**：`page=1`、`pageSize` 等于该列表缺省值、空 `keyword`、空筛选值、等于缺省值的单值参数，一律从 URL 删除，保持链接干净（设计 §8）。
- **不新增前端依赖**：不加 vitest/jest/@testing-library；`pnpm-lock.yaml` 不得出现 diff。
- **纯函数不得 import React / react-router**：`src/lib/listQueryParams.ts` 必须零依赖，否则 Node 侧断言跑不起来。
- **CI 等价校验全绿**：`pnpm --filter admin-web build`、`pnpm lint`、`pnpm format:check`（CI 用 Node 20，因此新增的 Node 测试脚本**不进 CI**）。
- **`pnpm format`（prettier）必须对新增/修改的 `frontend/` 文件生效**，否则 `format:check` 会红。
- 不要删除既有注释；新增逻辑按本仓风格写中文 JSDoc，说明「为什么」。

## 验证方式说明（重要）

本仓前端**没有测试框架**，`admin-web/tsconfig.json` 的 `include` 只有 `src`，CI 的 Node 是 20（不能执行 `.ts`）。因此：

- **纯逻辑**（URL 读写的语义，风险最集中）→ 用 `node:assert` 断言，脚本放**仓库根** `scripts/`（与既有 `scripts/check-perm-invariants.mjs` 同处：不受 frontend eslint/prettier 管辖，也就不需要为它调整工具链）。**需 Node ≥ 22.6**，本机 24 直接可跑。
- **组件与流程集成**（每个页面的迁移）→ 在每个 Task 里给出**精确的手工验收步骤与期望**，在已运行的 :5173 上执行。本计划不使用浏览器自动化。

## File Structure

| 文件 | 动作 | 职责 |
|------|------|------|
| `frontend/admin-web/src/lib/listQueryParams.ts` | 新建 | URL 参数读写的**纯逻辑**：`readPositiveInt` / `readListQuery` / `applyListPatch` / `findReservedFilterKeys`。零依赖。 |
| `frontend/admin-web/src/lib/listQuery.ts` | 新建 | React 封装：`useListQuery`（列表分页筛选）、`useUrlParam`（单值参数 + 可选编解码）。 |
| `scripts/test-list-query-params.mjs` | 新建 | 纯逻辑的行为断言（Node 原生）。 |
| `frontend/package.json` | 修改 | 加 `test:list-query` 脚本（不进 CI）。 |
| `frontend/admin-web/src/components/ResourcePage.tsx` | 修改 | 分页/关键字/筛选/视图 → URL；拉取改为 URL 驱动 + 过期响应守卫。**本 Task 修掉用户报的资产台账问题。** |
| `frontend/admin-web/src/pages/ProjectZonesPage.tsx` | 修改 | 切项目/分区改为合并式写入，并原子清掉右栏分页。 |
| `frontend/admin-web/src/components/ProjectListPane.tsx` | 修改 | 关键字/分页 → `projectPage` / `projectKeyword`。 |
| `frontend/admin-web/src/components/ZoneAssetPane.tsx` | 修改 | 资产分页 → `assetPage`。 |
| `frontend/admin-web/src/pages/ProjectDetailPage.tsx` | 修改 | 分区/状态/视图 → URL；档案链接补 `state.from`。 |
| `frontend/admin-web/src/pages/AssetMapPage.tsx` | 修改 | 城市/状态 → URL。 |
| `frontend/admin-web/src/pages/OpsCalendarPage.tsx` | 修改 | 月份/日期/事件类型 → URL；事件链接补 `state.from`。 |
| `frontend/admin-web/src/components/TableActions.tsx` | 修改 | 所有 `to` 链接自动带 `state.from`。 |
| `frontend/admin-web/src/pages/DunningAutoPage.tsx` | 修改 | 合同链接补 `state.from`。 |

---

## Task 1: 列表 URL 参数核心（纯逻辑 + React 封装）

**Files:**
- Create: `frontend/admin-web/src/lib/listQueryParams.ts`
- Create: `frontend/admin-web/src/lib/listQuery.ts`
- Create: `scripts/test-list-query-params.mjs`
- Modify: `frontend/package.json`（scripts 加一行）

**Interfaces:**
- Consumes: 无（本 Task 是起点）
- Produces（后续所有 Task 依赖，名字与签名必须一致）：
  - `interface ListQueryOptions { prefix?: string; defaultPageSize?: number; filterKeys?: string[] }`
  - `interface ListQueryState { page: number; pageSize: number; keyword: string; filters: Record<string, string> }`
  - `interface ListQueryPatch { page?: number; pageSize?: number; keyword?: string; filters?: Record<string, string> }`
  - `const readPositiveInt: (raw: string | null, fallback: number) => number`
  - `const readListQuery: (params: URLSearchParams, options?: ListQueryOptions) => ListQueryState`
  - `const applyListPatch: (params: URLSearchParams, patch: ListQueryPatch, options?: ListQueryOptions & { resetPage?: boolean }) => URLSearchParams`
  - `const findReservedFilterKeys: (filterKeys: string[]) => string[]`
  - `interface UrlParamCodec<T> { parse: (raw: string) => T; serialize: (value: T) => string }`
  - `function useListQuery(options?: ListQueryOptions): ListQueryState & { patch: (patch: ListQueryPatch, options?: { resetPage?: boolean }) => void; setPage: (page: number) => void; setPageSize: (pageSize: number) => void; setKeyword: (keyword: string) => void; setFilter: (key: string, value: string) => void; setFilters: (filters: Record<string, string>) => void }`
  - `function useUrlParam(key: string, defaultValue: string): [string, (next: string) => void]`
  - `function useUrlParam<T>(key: string, defaultValue: T, codec: UrlParamCodec<T>): [T, (next: T) => void]`

- [ ] **Step 1: 写纯逻辑模块**

Create `frontend/admin-web/src/lib/listQueryParams.ts`:

```ts
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
```

- [ ] **Step 2: 写测试脚本（先让它失败——模块还不存在）**

Create `scripts/test-list-query-params.mjs`:

```js
#!/usr/bin/env node
/**
 * 列表 URL 参数纯逻辑的行为断言（设计 §5.1 / §5.2、§8）。
 *
 * 运行：pnpm test:list-query（在 frontend/ 下）或 node scripts/test-list-query-params.mjs
 *
 * 为什么是「Node 原生断言」而不是 vitest：本仓前端没有测试框架，而这段逻辑
 * （正整数兜底 / 缺省值不写 / 合并式写入 / 筛选整体替换）正是最容易写歪、也最容易
 * 回归的部分。抽成零依赖纯模块后，用 Node 的类型剥离直接跑断言，不引入任何依赖。
 *
 * 注意：类型剥离需要 Node ≥ 22.6（本机 24）；CI 跑 Node 20，故本脚本**不进 CI**。
 */
import { strict as assert } from 'node:assert';
import {
  applyListPatch,
  findReservedFilterKeys,
  readListQuery,
  readPositiveInt,
} from '../frontend/admin-web/src/lib/listQueryParams.ts';

let passed = 0;
const check = (name, fn) => {
  fn();
  passed += 1;
};

/* ---------- readPositiveInt：非法页码一律回落，绝不产生非法请求 ---------- */
check('正整数原样返回', () => assert.equal(readPositiveInt('3', 1), 3));
check('小数回落（否则会请求 page=1.5 → 后端 400）', () =>
  assert.equal(readPositiveInt('1.5', 1), 1));
check('科学计数法回落', () => assert.equal(readPositiveInt('1e3', 1), 1));
check('十六进制回落', () => assert.equal(readPositiveInt('0x10', 1), 1));
check('0 回落', () => assert.equal(readPositiveInt('0', 1), 1));
check('负数回落', () => assert.equal(readPositiveInt('-1', 1), 1));
check('非数字回落', () => assert.equal(readPositiveInt('abc', 1), 1));
check('空串回落', () => assert.equal(readPositiveInt('', 1), 1));
check('缺失回落', () => assert.equal(readPositiveInt(null, 10), 10));
check('大数保留', () => assert.equal(readPositiveInt('999', 10), 999));

/* ---------- readListQuery：默认值 / 筛选 / 前缀 ---------- */
check('空 URL 得到默认状态', () =>
  assert.deepEqual(readListQuery(new URLSearchParams('')), {
    page: 1,
    pageSize: 10,
    keyword: '',
    filters: {},
  }));

check('完整 URL 被正确读出', () =>
  assert.deepEqual(
    readListQuery(new URLSearchParams('page=3&pageSize=20&keyword=%E7%94%B2&status=leased'), {
      filterKeys: ['status'],
    }),
    { page: 3, pageSize: 20, keyword: '甲', filters: { status: 'leased' } },
  ));

check('空筛选值视为未筛选', () =>
  assert.deepEqual(readListQuery(new URLSearchParams('status='), { filterKeys: ['status'] }).filters, {}));

check('前缀隔离：assetPage 只被 prefix=asset 读到', () => {
  const params = new URLSearchParams('assetPage=2&assetKeyword=x&page=9');
  assert.deepEqual(readListQuery(params, { prefix: 'asset' }), {
    page: 2,
    pageSize: 10,
    keyword: 'x',
    filters: {},
  });
  assert.equal(readListQuery(params, { prefix: '' }).page, 9);
});

check('未声明的参数不被解释（深链参数不得被当筛选发送）', () =>
  assert.deepEqual(readListQuery(new URLSearchParams('other=x'), { filterKeys: ['status'] }).filters, {}));

/* ---------- applyListPatch：合并 / 缺省值不写 / 不修改入参 ---------- */
check('翻页只改 page，其余参数原样保留', () => {
  const input = new URLSearchParams('status=leased&keyword=%E7%94%B2&projectId=7');
  const out = applyListPatch(input, { page: 3 }, { filterKeys: ['status'] });
  assert.equal(out.get('page'), '3');
  assert.equal(out.get('status'), 'leased');
  assert.equal(out.get('keyword'), '甲');
  assert.equal(out.get('projectId'), '7');
});

check('入参不被修改（纯函数）', () => {
  const input = new URLSearchParams('page=3');
  applyListPatch(input, { page: 5 });
  assert.equal(input.get('page'), '3');
});

check('page=1 不写进 URL', () =>
  assert.equal(applyListPatch(new URLSearchParams('page=3'), { page: 1 }).get('page'), null));

check('pageSize 等于缺省值时不写', () => {
  assert.equal(
    applyListPatch(new URLSearchParams(''), { pageSize: 10 }, { defaultPageSize: 10 }).get('pageSize'),
    null,
  );
  assert.equal(
    applyListPatch(new URLSearchParams(''), { pageSize: 50 }, { defaultPageSize: 10 }).get('pageSize'),
    '50',
  );
});

check('空关键字从 URL 删除', () =>
  assert.equal(applyListPatch(new URLSearchParams('keyword=x'), { keyword: '' }).get('keyword'), null));

check('筛选视为完整目标状态：未出现即清空', () => {
  const out = applyListPatch(
    new URLSearchParams('status=leased&contractId=12'),
    { filters: { status: 'vacant' } },
    { filterKeys: ['status', 'contractId'] },
  );
  assert.equal(out.get('status'), 'vacant');
  assert.equal(out.get('contractId'), null);
});

check('未声明的筛选键不被 patch 触及', () => {
  const out = applyListPatch(
    new URLSearchParams('other=1'),
    { filters: { status: 'leased' } },
    { filterKeys: ['status'] },
  );
  assert.equal(out.get('other'), '1');
});

check('resetPage 删掉 page（配合筛选/查询归 1）', () =>
  assert.equal(
    applyListPatch(new URLSearchParams('page=5'), {}, { resetPage: true }).get('page'),
    null,
  ));

check('显式 page 优先于 resetPage', () =>
  assert.equal(
    applyListPatch(new URLSearchParams(''), { page: 4 }, { resetPage: true }).get('page'),
    '4',
  ));

check('前缀写入互不干扰', () => {
  const out = applyListPatch(
    new URLSearchParams('page=2&assetPage=4'),
    { page: 3 },
    { prefix: 'asset' },
  );
  assert.equal(out.get('assetPage'), '3');
  assert.equal(out.get('page'), '2');
});

/* ---------- findReservedFilterKeys：筛选键撞保留字要能被发现 ---------- */
check('未撞保留字返回空', () => assert.deepEqual(findReservedFilterKeys(['status', 'city']), []));
check('撞保留字被列出', () =>
  assert.deepEqual(findReservedFilterKeys(['keyword', 'status', 'page']), ['keyword', 'page']));

console.log(`listQueryParams: ${passed} 项断言全部通过`);
```

- [ ] **Step 3: 加运行脚本**

修改 `frontend/package.json`，在 `"scripts"` 里 `"check:perm"` 之后加一行（注意 JSON 逗号）：

```json
    "check:perm": "node ../scripts/check-perm-invariants.mjs",
    "test:list-query": "node ../scripts/test-list-query-params.mjs",
```

- [ ] **Step 4: 跑测试，确认通过**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
node scripts/test-list-query-params.mjs
```

Expected: 打印 `listQueryParams: 25 项断言全部通过`（断言条数以实际为准，只要无 `AssertionError`）。

> 若报 `ERR_UNKNOWN_FILE_EXTENSION` 或 `Unknown file extension ".ts"`，说明 Node < 22.6 —— 本机是 24，正常情况不会出现。

- [ ] **Step 5: 写 React 封装**

Create `frontend/admin-web/src/lib/listQuery.ts`:

```ts
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  applyListPatch,
  findReservedFilterKeys,
  readListQuery,
  type ListQueryOptions,
  type ListQueryPatch,
  type ListQueryState,
} from '@/lib/listQueryParams';

/**
 * 列表的分页 / 关键字 / 筛选存进 URL，并把它作为**唯一真相**（设计 §5.2）。
 *
 * <p>为什么必须落在 URL：列表页跳到详情/表单是**路由变化**，列表组件会整页卸载，
 * 只存在组件 state 里的页码与筛选无法幸存，返回后必然回到第 1 页、丢掉全部筛选。
 * 附带收益：可刷新保持、链接可分享、浏览器前进后退可用。
 *
 * <p>写入用 `setSearchParams` 的**函数式更新**：同一事件里连续两次写入会依次叠加，
 * 不会后者覆盖前者（react-router 6.9+ 支持该签名）。
 */
export function useListQuery(options: ListQueryOptions = {}) {
  const { prefix = '', defaultPageSize = 10, filterKeys = [] } = options;
  const [searchParams, setSearchParams] = useSearchParams();

  // filterKeys 数组每次渲染都是新引用，用它的序列化值做依赖；否则 useMemo 每次都失效、
  // filters 换新对象，调用方的 useEffect 会被无限触发。
  const filterKeysKey = filterKeys.join(',');
  const resolvedFilterKeys = useMemo(
    () => (filterKeysKey ? filterKeysKey.split(',') : []),
    [filterKeysKey],
  );

  // 用 toString() 而不是 searchParams 对象做依赖：react-router 每次导航都会给出新的
  // URLSearchParams 实例，直接依赖它会让「参数其实没变」的重渲染也算作变化。
  const search = searchParams.toString();

  const state = useMemo(
    () => readListQuery(new URLSearchParams(search), { prefix, defaultPageSize, filterKeys: resolvedFilterKeys }),
    [search, prefix, defaultPageSize, resolvedFilterKeys],
  );

  // 开发态提示筛选键撞保留字 —— 生产不崩（设计 §8）
  if (import.meta.env?.DEV) {
    const reserved = findReservedFilterKeys(resolvedFilterKeys);
    if (reserved.length > 0) {
      console.warn(`[useListQuery] 筛选键与列表保留字冲突，会被互相覆盖：${reserved.join(', ')}`);
    }
  }

  const patch = useCallback(
    (next: ListQueryPatch, patchOptions: { resetPage?: boolean } = {}) => {
      setSearchParams(
        (prev) =>
          applyListPatch(prev, next, {
            prefix,
            defaultPageSize,
            filterKeys: resolvedFilterKeys,
            resetPage: patchOptions.resetPage,
          }),
        { replace: true },
      );
    },
    [setSearchParams, prefix, defaultPageSize, resolvedFilterKeys],
  );

  const setPage = useCallback((page: number) => patch({ page }), [patch]);
  const setPageSize = useCallback(
    (pageSize: number) => patch({ pageSize }, { resetPage: true }),
    [patch],
  );
  const setKeyword = useCallback(
    (keyword: string) => patch({ keyword }, { resetPage: true }),
    [patch],
  );
  const setFilters = useCallback(
    (filters: Record<string, string>) => patch({ filters }, { resetPage: true }),
    [patch],
  );
  /** 改单个筛选：其余筛选原样保留（`patch.filters` 是完整目标状态） */
  const setFilter = useCallback(
    (key: string, value: string) => patch({ filters: { ...state.filters, [key]: value } }, { resetPage: true }),
    [patch, state.filters],
  );

  return { ...state, patch, setPage, setPageSize, setKeyword, setFilter, setFilters };
}

/** 单值参数的编解码（dayjs、字符串数组等非字符串量） */
export interface UrlParamCodec<T> {
  parse: (raw: string) => T;
  serialize: (value: T) => string;
}

/**
 * 单值 URL 参数：非列表筛选的零散筛选量（项目详情选中的分区、地图选中的城市、
 * 日历的月份与事件类型）。与 {@link useListQuery} 同样的规则：函数式写入、
 * `replace`、等于缺省值就从 URL 删除（设计 §5.1）。
 */
export function useUrlParam(key: string, defaultValue: string): [string, (next: string) => void];
export function useUrlParam<T>(
  key: string,
  defaultValue: T,
  codec: UrlParamCodec<T>,
): [T, (next: T) => void];
export function useUrlParam<T>(key: string, defaultValue: T, codec?: UrlParamCodec<T>) {
  const [searchParams, setSearchParams] = useSearchParams();
  const raw = searchParams.get(key);

  const serialize = useCallback(
    (value: T): string => (codec ? codec.serialize(value) : String(value)),
    [codec],
  );

  const value = raw == null ? defaultValue : codec ? codec.parse(raw) : (raw as unknown as T);
  const defaultSerialized = serialize(defaultValue);

  const setValue = useCallback(
    (next: T) => {
      setSearchParams(
        (prev) => {
          const params = new URLSearchParams(prev);
          const serialized = serialize(next);
          if (serialized === defaultSerialized || serialized === '') params.delete(key);
          else params.set(key, serialized);
          return params;
        },
        { replace: true },
      );
    },
    [setSearchParams, key, serialize, defaultSerialized],
  );

  return [value, setValue] as [T, (next: T) => void];
}
```

- [ ] **Step 6: 类型检查通过**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm --filter admin-web build
```

Expected: `tsc -b` 无错误，vite 构建成功。此时 `useListQuery` / `useUrlParam` 尚未被任何页面使用，`noUnusedLocals: false` 允许存在未被引用的导出。

- [ ] **Step 7: 格式化并确认 lint 干净**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm format && pnpm lint && pnpm format:check
```

Expected: 三条命令退出码 0。

- [ ] **Step 8: 提交**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
git add frontend/admin-web/src/lib/listQueryParams.ts frontend/admin-web/src/lib/listQuery.ts scripts/test-list-query-params.mjs frontend/package.json
git commit -m "feat(admin): 新增列表 URL 查询参数核心（纯逻辑 + useListQuery/useUrlParam）"
```

---

## Task 2: 资产台账列表（ResourcePage）——修掉用户报的问题

**Files:**
- Modify: `frontend/admin-web/src/components/ResourcePage.tsx`

**Interfaces:**
- Consumes: `useListQuery`（Task 1）
- Produces: 无对外接口；`ResourcePage` 的行为契约变为「分页/关键字/筛选/视图由 URL 决定」

**背景**：这是用户报的原始问题（「资产台账列表进入详情或编辑时没有返回到跳转前的页」）。
`/assets` 由 `RESOURCES.assets` 驱动、统一渲染 `ResourcePage`，所以本 Task 同时修好
项目、合同、账单、租户等所有走 `ResourcePage` 的列表。

- [ ] **Step 1: 复现（修复前的失败验收）**

在已运行的前端上手工复现，确认症状：

1. 打开 `http://localhost:5173/assets`。
2. 在「租控状态」筛选里选一个值，在搜索框输入一个关键字并回车，翻到第 2 页。
3. **观察并记录此时浏览器地址栏** —— 期望（修复后）应形如
   `/assets?status=leased&keyword=甲&page=2`；**修复前它只是 `/assets`**。
4. 点某一行进入「一物一档」，再点页面左上「返回原页面」。
5. 修复前实际：回到 `/assets`，页码是 1、筛选与关键字全空。**这就是要修的 bug。**

- [ ] **Step 2: 改 import 与顶部状态**

在 `ResourcePage.tsx` 的 import 区加入（紧邻既有 `import { currentPath } from '@/lib/navigation';`）：

```ts
import { useListQuery } from '@/lib/listQuery';
```

把（约 694–698 行的）本地状态：

```ts
  const [data, setData] = useState<PageResult<Row>>({ list: [], total: 0, page: 1, pageSize: 10 });
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [filters, setFilters] = useState<Record<string, string>>({});
```

替换为：

```ts
  const [data, setData] = useState<PageResult<Row>>({ list: [], total: 0, page: 1, pageSize: 10 });

  /**
   * 分页 / 关键字 / 筛选以 **URL 为唯一真相**（设计 §5.2）。
   *
   * <p>这是「跳详情或编辑再返回能回到跳转前那一页」的前提：那是路由变化，本组件会整页
   * 卸载，只存在组件 state 里的页码与筛选无法幸存。
   */
  const filterKeys = useMemo(
    () => [...(config.filters ?? []), ...(config.tagFilters ?? [])].map((f) => f.key),
    [config.filters, config.tagFilters],
  );
  const {
    page,
    pageSize,
    keyword,
    filters,
    patch: patchListQuery,
    setPage,
    setKeyword,
    setFilters,
  } = useListQuery({ filterKeys });

  /** 关键字输入框草稿：只有回车/点「查询」才写进 URL，避免每敲一个字都发请求（设计 §8） */
  const [kwDraft, setKwDraft] = useState(keyword);
  useEffect(() => setKwDraft(keyword), [keyword]);

  /** 重拉信号：条件没变时（刷新、查询同一个关键字）靠它触发一次请求 */
  const [reloadToken, setReloadToken] = useState(0);

  /** 请求序号：快速翻页时丢弃过期响应，避免旧响应覆盖新数据（设计 §8） */
  const seqRef = useRef(0);
```

- [ ] **Step 3: 给 `load` 加过期响应守卫**

把 `load` 的开头（约 752–753 行）：

```ts
  const load = async (p = page, size = pageSize, kw = keyword, flt = filters) => {
    setLoading(true);
    try {
```

改为：

```ts
  const load = async (p = page, size = pageSize, kw = keyword, flt = filters) => {
    // 序号守卫：每次请求自增，只有「最后一次」的响应可以写状态。
    // 快速翻页会并发多个请求，先发后到的旧响应会把新数据覆盖回上一页的内容。
    const seq = ++seqRef.current;
    const stale = () => seq !== seqRef.current;
    setLoading(true);
    try {
```

把 try 内成功分支：

```ts
      setData(normalizePage(raw, p, size));
    } catch (e) {
      setData({ list: [], total: 0, page: p, pageSize: size });
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
```

改为：

```ts
      if (stale()) return;
      setData(normalizePage(raw, p, size));
    } catch (e) {
      if (stale()) return;
      setData({ list: [], total: 0, page: p, pageSize: size });
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      // 过期请求不得清掉后发请求的 loading
      if (!stale()) setLoading(false);
    }
```

并把统计条那一块的条件（约 771 行）：

```ts
    if (config.statsSource) {
```

改为：

```ts
    if (config.statsSource && !stale()) {
```

- [ ] **Step 4: 把「重置 effect」换成 URL 驱动的拉取 effect**

把（约 790–795 行）：

```ts
  useEffect(() => {
    setPage(1);
    setFilters({});
    setKeyword('');
    load(1, pageSize, '', {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config.listPath]);
```

替换为：

```ts
  /**
   * 条件变化 → 按 **URL 里的条件** 拉取（设计 §5.3）。
   *
   * <p>取代了原来的「改 state + 显式调 load()」：那样在列表内点浏览器后退时 URL 变了、
   * 组件却没卸载，表格会停在旧数据上。
   *
   * <p>原「按 listPath 重置」的 effect 已删除：路由切换后 URL 本身不带 query，
   * 读出来就是第 1 页 + 无筛选，无需再显式重置。
   *
   * <p>`config` 必须留在依赖里 —— 切换资源（`/assets` → `/projects`）时组件是**复用**
   * 而非重新挂载，而两者 URL 都没有 query，只靠 page/keyword/filters 不会变化，
   * 漏掉 config 就永远不会重新拉取。
   */
  useEffect(() => {
    void load(page, pageSize, keyword, filters);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, pageSize, keyword, filters, reloadToken, config]);
```

- [ ] **Step 5: 关键字输入框与「查询 / 刷新」按钮**

在 `setFilterValue` 之前（约 982 行前）新增查询处理：

```ts
  /** 查询：关键字写入 URL（页码自动归 1）；关键字没变时靠重拉信号触发一次请求 */
  const handleSearch = () => {
    if (kwDraft === keyword) setReloadToken((token) => token + 1);
    else setKeyword(kwDraft);
  };
```

把搜索输入框（约 1119–1128 行）：

```tsx
            <Input
              allowClear
              placeholder="搜索关键字"
              prefix={<SearchOutlined className="text-gray-400" />}
              className="!w-[160px] sm:!w-[200px]"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={() => {
                setPage(1);
                load(1, pageSize, keyword, filters);
              }}
            />
```

改为：

```tsx
            <Input
              allowClear
              placeholder="搜索关键字"
              prefix={<SearchOutlined className="text-gray-400" />}
              className="!w-[160px] sm:!w-[200px]"
              value={kwDraft}
              onChange={(e) => setKwDraft(e.target.value)}
              onPressEnter={handleSearch}
            />
```

把「查询」与「刷新」两个按钮（约 1130–1142 行）：

```tsx
            <Button
              icon={<SearchOutlined />}
              onClick={() => {
                setPage(1);
                load(1, pageSize, keyword, filters);
              }}
            >
              查询
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => load(page, pageSize, keyword, filters)}
            />
```

改为：

```tsx
            <Button icon={<SearchOutlined />} onClick={handleSearch}>
              查询
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => setReloadToken((token) => token + 1)}
              aria-label="刷新列表"
            />
```

- [ ] **Step 6: 筛选点击改为写 URL**

把 `setFilterValue`（约 982–992 行）：

```ts
  const setFilterValue = (key: string, value: string) => {
    if (filters[key] === value) return; // 重复点击同一标签不重复请求
    const nf: Record<string, string> = { ...filters, [key]: value };
    // 级联子项：父级取值变化后清空，避免残留与父级不匹配的选项（如换成土地类还留着「投资建设」）
    (config.tagFilters ?? []).forEach((tf) => {
      if (tf.cascadeParentKey === key) nf[tf.key] = '';
    });
    setFilters(nf);
    setPage(1);
    load(1, pageSize, keyword, nf);
  };
```

改为：

```ts
  const setFilterValue = (key: string, value: string) => {
    if (filters[key] === value) return; // 重复点击同一标签不重复请求
    const nf: Record<string, string> = { ...filters, [key]: value };
    // 级联子项：父级取值变化后清空，避免残留与父级不匹配的选项（如换成土地类还留着「投资建设」）
    (config.tagFilters ?? []).forEach((tf) => {
      if (tf.cascadeParentKey === key) nf[tf.key] = '';
    });
    // setFilters 内部把页码归 1（设计 §5.2），不需要再显式 setPage(1)
    setFilters(nf);
  };
```

- [ ] **Step 7: 其余「改完就重拉」的调用点改为重拉信号**

组件内一共 5 处「改完就重拉」的调用点，分两类改。

**第一类（3 处，留在当前页）：** 把成功分支里的 `load(page, pageSize, keyword, filters);` 换成：

```ts
      setReloadToken((token) => token + 1);
```

1. `handleUpdate`（约 852 行）
2. `handleSubmitRowAction`（约 913 行）
3. `handleDelete` 的 `onOk`（约 937 行）

**第二类（2 处，回到第 1 页）：** 把两行：

```ts
      setPage(1);
      load(1, pageSize, keyword, filters);
```

换成：

```ts
      // 回到第 1 页 + 重拉：两次 setState 会被 React 批处理，effect 只跑一次
      setPage(1);
      setReloadToken((token) => token + 1);
```

1. `handleCreate` 成功分支（约 823–824 行）
2. 导入成功分支（约 1182 行）

> 注意：`load` 的签名仍保留「可传参覆盖」的形式（默认值取自 URL 派生值），因此上述替换后
> 组件内不再有「显式传参调 load」的地方；`load` 只由 Step 4 的 effect 调用。

- [ ] **Step 8: 分页器改为一次写入**

把分页器（约 1310–1323 行）：

```tsx
        <Pagination
          current={page}
          pageSize={pageSize}
          total={data.total}
          showSizeChanger
          showTotal={(t) => `共 ${t} 条`}
          responsive
          onChange={(p, size) => {
            setPage(p);
            setPageSize(size);
            load(p, size, keyword, filters);
          }}
        />
```

改为：

```tsx
        <Pagination
          current={page}
          pageSize={pageSize}
          total={data.total}
          showSizeChanger
          showTotal={(t) => `共 ${t} 条`}
          responsive
          // 一次写入两个参数：分两次 patch 会先按旧 pageSize 拉一次、再补拉一次
          onChange={(p, size) => patchListQuery({ page: p, pageSize: size })}
        />
```

- [ ] **Step 9: 视图模式也进 URL（不进拉取依赖）**

把（约 718 行）：

```ts
  const [viewMode, setViewMode] = useState<'list' | 'card'>('list');
```

改为：

```ts
  /** 列表 / 卡片视图；仅在配置了 config.card 时可用。进 URL 以便返回后还原，但不参与拉取 */
  const [viewModeParam, setViewMode] = useUrlParam('view', 'list');
  const viewMode: 'list' | 'card' = viewModeParam === 'card' ? 'card' : 'list';
```

并 import：

```ts
import { useListQuery, useUrlParam } from '@/lib/listQuery';
```

> `viewMode` **不得**进入 Step 4 那个 effect 的依赖 —— 切视图只换渲染方式，不该重新请求。

- [ ] **Step 10: 构建、lint、格式化**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm --filter admin-web build && pnpm lint && pnpm format:check
```

Expected: 全部退出码 0。若 `format:check` 报 `ResourcePage.tsx`，跑 `pnpm format` 后重试。

- [ ] **Step 11: 手工验收（Task 的核心交付）**

前端 :5173、后端 :8080 已运行。逐条确认：

1. `/assets` → 选「租控状态」+ 输入关键字回车 + 翻到第 2 页 →
   **地址栏应为** `/assets?status=xxx&keyword=yyy&page=2`。
2. 点行进入「一物一档」→ 点「返回原页面」→ **页码、筛选、关键字全部还原**（验收标准 1、2）。
3. 在还原后的 URL 上按 F5 刷新 → 条件与页码仍在、数据一致（验收标准 3）。
4. 在列表内从第 2 页翻回第 1 页，再点浏览器**后退** → 回到第 2 页，且**表格数据随之切换到第 2 页**（不出现「URL 变了、数据没变」）（验收标准 4）。
5. 点行「编辑」→ 在表单页点「取消」→ 同样还原。
6. 关键字输入框只打字不回车 → **地址栏不变、不发请求**；回车后才写入 URL。
7. 点击「筛选标签」重复点同一个值 → 不重复发请求（保留既有行为）。
8. 点侧边栏切到「项目列表」再切回「资产台账」→ 回到干净的 `/assets`（第 1 页、无筛选）。
9. 卡片/列表视图切换后进入详情再返回 → 视图模式还原；且切视图**不产生网络请求**（Network 面板确认）。

- [ ] **Step 12: 提交**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
git add frontend/admin-web/src/components/ResourcePage.tsx
git commit -m "fix(admin): 列表分页/关键字/筛选进 URL，资产台账返回后回到跳转前的页"
```

---

## Task 3: 项目分区管理（左栏项目 / 右栏资产分页进 URL）

**Files:**
- Modify: `frontend/admin-web/src/components/ProjectListPane.tsx`
- Modify: `frontend/admin-web/src/components/ZoneAssetPane.tsx`
- Modify: `frontend/admin-web/src/pages/ProjectZonesPage.tsx`

**Interfaces:**
- Consumes: `useListQuery`（Task 1）
- Produces: `ZoneAssetPane.tsx` 导出 `export const ASSET_PAGE_PARAM = 'assetPage';`，供 `ProjectZonesPage` 在切换项目/分区时原子清掉右栏分页

**背景**：本页一屏内有两套列表（左栏项目、右下资产），共用同一个 URL，所以两套列表必须
加前缀，且父级切换必须用**合并式**写入。现状 `selectProject`/`selectZone` 是整包替换
`{ projectId, zoneId }`，一旦左栏分页进了 URL 就会被整包抹掉。

- [ ] **Step 1: 左栏项目列表（ProjectListPane）**

把 import 区加入：

```ts
import { useListQuery } from '@/lib/listQuery';
```

把（约 32–36 行）：

```ts
  const [keyword, setKeyword] = useState('');
  /** 已提交的关键字：与输入框分离，避免每敲一个字都发请求 */
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
```

替换为：

```ts
  /** 输入框草稿（本地）：与 URL 上已提交的关键字分离，避免每敲一个字都发请求 */
  const [keyword, setKeyword] = useState('');
  /**
   * 已提交的关键字与页码进 URL（设计 §4）：从本页跳去资产表单再返回时本组件会重新挂载，
   * 只在 state 里就会丢。加 `project` 前缀是因为同一 URL 上右栏资产表也有分页。
   */
  const {
    page,
    keyword: query,
    setPage,
    setKeyword: setQuery,
  } = useListQuery({ prefix: 'project', defaultPageSize: PAGE_SIZE });

  /** URL（前进/后退、外部链接）变化时把输入框同步回来 */
  useEffect(() => setKeyword(query), [query]);
```

把 `handleSearch`（约 75–79 行）：

```ts
  /** 查询：页码归 1；关键字没变时靠 reloadToken 重拉（同值 setState 不会触发 effect） */
  const handleSearch = () => {
    setPage(1);
    if (query === keyword) setReloadToken((token) => token + 1);
    else setQuery(keyword);
  };
```

改为：

```ts
  /** 查询：关键字写入 URL（setKeyword 内部把页码归 1）；关键字没变时靠 reloadToken 重拉 */
  const handleSearch = () => {
    if (query === keyword) setReloadToken((token) => token + 1);
    else setQuery(keyword);
  };
```

分页器的 `onChange={(p) => setPage(p)}` 保持不变（`setPage` 现在是写 URL）。

> `load` 与 `useEffect(() => { void load(page, query); }, [load, page, query, reloadToken]);`
> 保持原样 —— `page`/`query` 现在是 URL 派生值，URL 一变 effect 自然重跑。

- [ ] **Step 2: 右栏资产分页（ZoneAssetPane）**

把 import 区加入：

```ts
import { useListQuery } from '@/lib/listQuery';
```

并在 `ALL_ZONES_KEY` / `PAGE_SIZE` 常量旁新增导出：

```ts
/**
 * 右栏资产分页的 URL 键名。
 *
 * <p>导出给 {@link ProjectZonesPage}：切换项目/分区时它必须**原子地**把这个键删掉
 * （回到第 1 页），否则会先按上一个分区的旧页码发一次请求再补发第 1 页。
 */
export const ASSET_PAGE_PARAM = 'assetPage';
```

把（约 95 行）：

```ts
  const [page, setPage] = useState(1);
```

替换为：

```ts
  /** 资产分页进 URL（设计 §4）：跳资产表单再返回时本页会整页重新挂载 */
  const { page, setPage } = useListQuery({
    prefix: 'asset',
    defaultPageSize: PAGE_SIZE,
  });
```

把「上下文变化 → 归第 1 页并重拉」的 effect（约 132–146 行）：

```ts
  useEffect(() => {
    setPage(1);
    void loadAssets(1);
    // loadAssets 的依赖已覆盖 projectId / zoneId / canViewLedger；把它列进依赖会因
    // identity 变化而重复触发（同 ResourcePage 的处理）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, zoneId, canViewLedger, assetsReloadToken]);
```

替换为：

```ts
  /**
   * 项目 / 分区 / 页码 / 重拉信号变化 → 按当前条件拉取。
   *
   * <p>**不再在这里 `setPage(1)`**：切换项目或分区时由父级（{@link ProjectZonesPage}）
   * 把 {@link ASSET_PAGE_PARAM} 从 URL 删掉，本组件下一次渲染拿到的就已经是第 1 页，
   * 只发一次请求（原实现会先用旧页码发一次、再补发第 1 页）。
   */
  useEffect(() => {
    void loadAssets(page);
  }, [loadAssets, page, assetsReloadToken]);
```

把（约 170–173 行）：

```ts
  const handlePageChange = (p: number) => {
    setPage(p);
    void loadAssets(p);
  };
```

替换为：

```ts
  /** 翻页只写 URL：上面的 effect 会以新页码拉取，避免「显式 load + effect」双发 */
  const handlePageChange = (p: number) => setPage(p);
```

把 `handleRefresh`（约 184–187 行）：

```ts
  const handleRefresh = () => {
    void reloadZones();
    reloadAssets();
  };
```

替换为：

```ts
  /** 刷新必须同时重拉 Tab 栏与资产区，且资产**归第 1 页**（spec §5.2 第 7 条） */
  const handleRefresh = () => {
    void reloadZones();
    setPage(1);
    reloadAssets();
  };
```

> `loadAssets` 的依赖数组 `[projectId, zoneId, canViewLedger]` 保持不变 —— `page` 由
> effect 负责，放进 `loadAssets` 会让「页码变化」与「effect 触发」各发一次。

- [ ] **Step 3: 父级切换改为合并式写入（ProjectZonesPage）**

把 import 区：

```ts
import { ZoneAssetPane } from '@/components/ZoneAssetPane';
```

改为：

```ts
import { ASSET_PAGE_PARAM, ZoneAssetPane } from '@/components/ZoneAssetPane';
```

把 `selectProject` 与 `selectZone`（约 43–61 行）：

```ts
  const selectProject = useCallback(
    (id: number) => setSearchParams({ projectId: String(id) }, { replace: true }),
    [setSearchParams],
  );

  /** 切换分区：null 表示「全部分区」，对应 URL 上 `zoneId` 缺省 */
  const selectZone = useCallback(
    (id: number | null) => {
      if (projectId == null) return;
      setSearchParams(
        id == null
          ? { projectId: String(projectId) }
          : { projectId: String(projectId), zoneId: String(id) },
        { replace: true },
      );
    },
    [projectId, setSearchParams],
  );
```

替换为：

```ts
  /**
   * 切换项目：**不保留 `zoneId`**（回到「全部分区」，spec §5.2 第 1 条），
   * 并清掉右栏资产分页（它与分区强相关，换项目后没有意义）。
   *
   * <p>用合并式写入而不是整包替换：左栏项目的 `projectPage` / `projectKeyword` 必须保留，
   * 整包替换会把左栏的搜索与分页一并抹掉。用 replace 写入：切换项目/Tab 不该在浏览器
   * 历史里留下每一步。
   */
  const selectProject = useCallback(
    (id: number) =>
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('projectId', String(id));
          next.delete('zoneId');
          next.delete(ASSET_PAGE_PARAM);
          return next;
        },
        { replace: true },
      ),
    [setSearchParams],
  );

  /**
   * 切换分区：null 表示「全部分区」，对应 URL 上 `zoneId` 缺省。
   * 同样清掉右栏资产分页（spec §5.2 第 2 条：分页归 1、重拉）。
   */
  const selectZone = useCallback(
    (id: number | null) => {
      if (projectId == null) return;
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('projectId', String(projectId));
          if (id == null) next.delete('zoneId');
          else next.set('zoneId', String(id));
          next.delete(ASSET_PAGE_PARAM);
          return next;
        },
        { replace: true },
      );
    },
    [projectId, setSearchParams],
  );
```

同时更新该文件的顶部 JSDoc，把「选中态以 URL query 为唯一真相」的说明补一句分页：

```ts
 * <p><strong>选中态与各栏分页都以 URL query 为唯一真相</strong>：跳去资产表单页再返回时本页会整页
 * 重新挂载，state 全部丢失，选中项与页码不落在 URL 上就回不来。
```

- [ ] **Step 4: 构建、lint、格式化**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm --filter admin-web build && pnpm lint && pnpm format:check
```

Expected: 全部退出码 0。

- [ ] **Step 5: 手工验收**

1. `/project-zones` → 左栏搜索一个关键字、翻到第 2 页。
2. 点某个项目 → **左栏关键字与页码保留**（这是本次新增能力），地址栏形如
   `/project-zones?projectPage=2&projectKeyword=xxx&projectId=7`；右下资产表在第 1 页。
3. 右下翻到第 2 页 → 地址栏出现 `assetPage=2`。
4. 切换分区 Tab → `assetPage` 被清掉、资产回到第 1 页；`zoneId` 更新。
5. 切换项目 → `zoneId` 与 `assetPage` 都被清掉；左栏搜索仍在。
6. 右下点「新增资产」→ 表单页返回 → 左栏搜索/分页与右栏分区/页码**各自还原**（验收标准 5）。
7. 右下「刷新」→ 分区 Tab 与资产同时重拉，资产回到第 1 页。
8. 翻到第 2 页后点浏览器后退 → 回到第 1 页且数据同步切换。

- [ ] **Step 6: 提交**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
git add frontend/admin-web/src/components/ProjectListPane.tsx frontend/admin-web/src/components/ZoneAssetPane.tsx frontend/admin-web/src/pages/ProjectZonesPage.tsx
git commit -m "feat(admin): 项目分区管理的左右两栏分页与关键字进 URL（合并式写入）"
```

---

## Task 4: 项目详情页（分区 / 状态 / 视图进 URL）

**Files:**
- Modify: `frontend/admin-web/src/pages/ProjectDetailPage.tsx`

**Interfaces:**
- Consumes: `useUrlParam`（Task 1）、`currentPath`（既有）
- Produces: 无

- [ ] **Step 1: 换 state 为 URL 参数**

把 import 区：

```ts
import { useBackNavigate } from '@/lib/navigation';
```

改为：

```ts
import { currentPath, useBackNavigate } from '@/lib/navigation';
import { useUrlParam } from '@/lib/listQuery';
```

把（约 166–168 行）：

```ts
  const [zoneTab, setZoneTab] = useState<string>('all');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [assetView, setAssetView] = useState<'floor' | 'list'>('floor');
```

替换为：

```ts
  /**
   * 分区 / 租控状态 / 视图进 URL（设计 §4）：从本页点「一物一档」再返回时本页会重新挂载，
   * 只在 state 里就会丢。三者都是**客户端筛选**（资产一次拉 500 条在内存里筛），
   * 所以 URL 变化只触发 useMemo 重算，不产生请求。
   */
  const [zoneTab, setZoneTab] = useUrlParam('zone', 'all');
  const [statusFilter, setStatusFilter] = useUrlParam('status', '');
  const [assetViewParam, setAssetView] = useUrlParam('view', 'floor');
  /** 只认 floor / list，手改链接写成别的值也不至于渲染不出内容 */
  const assetView: 'floor' | 'list' = assetViewParam === 'list' ? 'list' : 'floor';
```

- [ ] **Step 2: 手改 URL 的自愈**

**必须插在 `statusOrder` 的 `useMemo` 之后**（约 270 行，即 `statusOrder` 定义完、`visibleAssets` 那一带），
**不能**放在 `load` 的 `useEffect`（约 197 行）附近 —— `useEffect` 的依赖数组是在**渲染期**求值的，
若 `statusOrder` 的 `const` 还没执行到，`[..., statusOrder, ...]` 会直接抛
`Cannot access 'statusOrder' before initialization`（TDZ）。

新增：

```ts
  /**
   * URL 里的 `zone` / `status` 若不属于当前数据（手改链接、换了项目、枚举被改）
   * → 回落到默认，避免「URL 说筛了某分区、表格却空着且看不出原因」。
   *
   * <p>`overview` 未加载完时直接返回：那时 zones / statusOrder 都是空的，
   * 提前自愈会把合法的筛选值误清掉。自愈会连带把该参数从 URL 删除（等于缺省值不写）。
   */
  useEffect(() => {
    if (!overview) return;
    if (zoneTab === 'all') return;
    if (overview.zones.some((z) => String(z.id) === zoneTab)) return;
    setZoneTab('all');
  }, [overview, zoneTab, setZoneTab]);

  useEffect(() => {
    if (!overview) return;
    if (!statusFilter) return;
    if (statusOrder.includes(statusFilter)) return;
    setStatusFilter('');
  }, [overview, statusFilter, statusOrder, setStatusFilter]);
```

> 放在 `statusOrder` 之后是硬性要求（见上）；`statusOrder` 是 `useMemo` 的稳定引用，
> 作为依赖不会引起额外重算。

- [ ] **Step 3: 档案链接补 `state.from`**

把（约 682–686 行）：

```tsx
                          <Link
                            to={`/assets/${asset.id}/dossier`}
                            className="text-xs text-blue-600 hover:text-blue-700 shrink-0"
                          >
```

改为：

```tsx
                          <Link
                            to={`/assets/${asset.id}/dossier`}
                            state={{ from: currentPath(location) }}
                            className="text-xs text-blue-600 hover:text-blue-700 shrink-0"
                          >
```

并在组件内取 location：

```ts
  const location = useLocation();
```

同时把 `import { Link, useParams } from 'react-router-dom';` 改为：

```ts
import { Link, useLocation, useParams } from 'react-router-dom';
```

- [ ] **Step 4: 构建、lint、格式化**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm --filter admin-web build && pnpm lint && pnpm format:check
```

Expected: 全部退出码 0。

- [ ] **Step 5: 手工验收**

1. 从资产台账进某项目详情 → 底部左侧选一个分区、点一个租控状态标签、切到「列表」视图 →
   地址栏应形如 `/projects/7?zone=3&status=vacant&view=list`。
2. 从底部资产的「一物一档」进档案 → 返回 → **分区、状态、视图三者还原**（验收标准 6）。
3. 直接刷新 → 三者仍在，数据一致。
4. 手改地址栏 `zone=99999` 回车 → 自动回落到「全部分区」且 URL 中的 `zone` 消失。
5. 手改 `status=not_a_status` → 自动回落「不限」。
6. 切分区/状态/视图 → **不发网络请求**（Network 面板确认）。

- [ ] **Step 6: 提交**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
git add frontend/admin-web/src/pages/ProjectDetailPage.tsx
git commit -m "feat(admin): 项目详情的分区/状态/视图进 URL 并按来源返回"
```

---

## Task 5: 资产地图（城市 / 状态进 URL）

**Files:**
- Modify: `frontend/admin-web/src/pages/AssetMapPage.tsx`

**Interfaces:**
- Consumes: `useUrlParam`（Task 1）
- Produces: 无

- [ ] **Step 1: 换 state 为 URL 参数**

把 import 区加入：

```ts
import { useUrlParam } from '@/lib/listQuery';
```

把（约 79–80 行）：

```ts
  const [city, setCity] = useState<string>();
  const [statusFilter, setStatusFilter] = useState<string>();
```

替换为：

```ts
  /**
   * 城市 / 租控状态进 URL（设计 §4）：从本页点「查看资产档案」再返回时本页会重新挂载。
   * 两者都是客户端筛选，URL 变化只触发 useMemo 重算，不产生请求。
   * `selectedId` 刻意**不**进 URL：它是「当前看哪个点」的瞬态选择，且默认值依赖数据加载。
   */
  const [city, setCity] = useUrlParam('city', '');
  const [statusFilter, setStatusFilter] = useUrlParam('status', '');
```

把两个 Select（约 194–219 行）的 `value` / `onChange` 改为「空串 ⇄ undefined」：

```tsx
            value={city || undefined}
            onChange={(v) => setCity(v ?? '')}
```

```tsx
            value={statusFilter || undefined}
            onChange={(v) => setStatusFilter(v ?? '')}
```

> antd `allowClear` 在清空时回传 `undefined`，所以必须用 `?? ''` 归一，否则会把
> `undefined` 写进 `String(undefined)`。

- [ ] **Step 2: 构建、lint、格式化**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm --filter admin-web build && pnpm lint && pnpm format:check
```

Expected: 全部退出码 0。

- [ ] **Step 3: 手工验收**

1. `/asset-map` → 选一个城市、选一个状态 → 地址栏形如 `/asset-map?city=%E6%9D%AD%E5%B7%9E%E5%B8%82&status=leased`。
2. 点「查看资产档案」→ 返回 → **城市与状态还原**（验收标准 7）。
3. 直接刷新 → 还原一致。
4. 点 Select 的清除按钮 → 对应的 URL 参数消失、列表恢复全量。
5. 切换城市/状态 → **不发网络请求**。

- [ ] **Step 4: 提交**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
git add frontend/admin-web/src/pages/AssetMapPage.tsx
git commit -m "feat(admin): 资产地图的城市/状态筛选进 URL"
```

---

## Task 6: 经营日历（月份 / 日期 / 事件类型进 URL）

**Files:**
- Modify: `frontend/admin-web/src/pages/OpsCalendarPage.tsx`

**Interfaces:**
- Consumes: `useUrlParam`、`UrlParamCodec`（Task 1）、`currentPath`（既有）
- Produces: 无

**背景**：三个参数里 `types` 是字符串数组、`month`/`date` 是 dayjs，需要编解码。
注意本仓**没有**加载 dayjs 的 `customParseFormat` 插件（全仓无 `dayjs.extend`），所以
严格解析要自己用「格式化回写比对」实现。

- [ ] **Step 1: 加编解码与模块级常量**

在 `TYPE_MAP` 定义之后（约 62 行后）新增：

```ts
/** 事件类型的**全选**集合：模块级常量，保证 useUrlParam 的默认值引用稳定 */
const ALL_TYPES = TYPE_OPTIONS.map((t) => t.value);

/**
 * 严格解析日期：本仓未加载 dayjs 的 customParseFormat 插件，`dayjs(raw, fmt, true)`
 * 的第三参数不生效，且宽松解析会把 `2026-13`「修」成 2027-01。
 * 用「格式化回写后与原文一致」做严格性判定，不依赖插件。
 */
const parseStrict = (raw: string, format: 'YYYY-MM' | 'YYYY-MM-DD'): Dayjs | null => {
  const value = dayjs(raw);
  if (!value.isValid()) return null;
  return value.format(format) === raw ? value : null;
};

/** 月份：`2026-09`；非法值回落当天 */
const MONTH_CODEC: UrlParamCodec<Dayjs> = {
  parse: (raw) => parseStrict(raw, 'YYYY-MM') ?? dayjs(),
  serialize: (value) => value.format('YYYY-MM'),
};

/** 选中日期：`2026-09-12`；非法值回落当天 */
const DATE_CODEC: UrlParamCodec<Dayjs> = {
  parse: (raw) => parseStrict(raw, 'YYYY-MM-DD') ?? dayjs(),
  serialize: (value) => value.format('YYYY-MM-DD'),
};

/**
 * 事件类型：逗号分隔；**缺省（参数不存在）= 全选**，`none` = 一个都不选。
 *
 * <p>用显式的 `none` 而不是空串：空串在 URL 上既是「没选」又像「没写」，
 * 与「缺省即全选」无法区分（设计 §8 要求不写空值参数）。
 */
const TYPES_CODEC: UrlParamCodec<string[]> = {
  parse: (raw) => {
    if (raw === 'none') return [];
    const known = raw.split(',').filter((value) => TYPE_MAP[value] != null);
    return known.length > 0 ? known : ALL_TYPES;
  },
  serialize: (value) => (value.length === 0 ? 'none' : value.join(',')),
};
```

并把 import 区加入：

```ts
import { currentPath } from '@/lib/navigation';
import { useUrlParam, type UrlParamCodec } from '@/lib/listQuery';
import { Link, useLocation } from 'react-router-dom';
```

> 若该文件原本 `import { Link } from 'react-router-dom';`，替换为上面这一行。

- [ ] **Step 2: 换 state 为 URL 参数**

把（约 71–74 行）：

```ts
  const [panelDate, setPanelDate] = useState(() => dayjs());
  const [selectedDate, setSelectedDate] = useState(() => dayjs());
  const [types, setTypes] = useState<string[]>(TYPE_OPTIONS.map((t) => t.value));
```

替换为：

```ts
  /**
   * 面板月份 / 选中日期 / 事件类型进 URL（设计 §4）：从本页点某事件的「处理」跳走后
   * 本页会重新挂载，只在 state 里就会丢。
   */
  const today = useMemo(() => dayjs(), []);
  const [panelDate, setPanelDate] = useUrlParam('month', today, MONTH_CODEC);
  const [selectedDate, setSelectedDate] = useUrlParam('date', today, DATE_CODEC);
  const [types, setTypes] = useUrlParam('types', ALL_TYPES, TYPES_CODEC);
```

> `value={types}` 与 `onChange={(v) => setTypes(v as string[])}` 保持不变：
> Checkbox.Group 的 `onChange` 在全部取消勾选时给出 `[]`，与 `types=none` 对应。

- [ ] **Step 3: 事件链接补 `state.from`**

在组件内取 location：

```ts
  const location = useLocation();
```

把（约 283–289 行）的：

```tsx
                          <Link
                            to={ev.linkPath}
                            className="text-xs text-[var(--ams-primary)] flex items-center gap-0.5"
                            aria-label={`查看 ${ev.title}`}
                          >
```

改为：

```tsx
                          <Link
                            to={ev.linkPath}
                            state={{ from: currentPath(location) }}
                            className="text-xs text-[var(--ams-primary)] flex items-center gap-0.5"
                            aria-label={`查看 ${ev.title}`}
                          >
```

- [ ] **Step 4: 构建、lint、格式化**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm --filter admin-web build && pnpm lint && pnpm format:check
```

Expected: 全部退出码 0。

- [ ] **Step 5: 手工验收**

1. `/ops-calendar` → 点日历的「‹」切到上个月 → 地址栏出现 `month=2026-08`。
2. 点某一天 → 地址栏出现 `date=2026-08-1x`。
3. 取消勾选部分事件类型 → 地址栏出现 `types=contract_expiry,bill_due` 之类的子集。
4. 点某事件的「处理」→ 返回 → **月份、日期、类型勾选三者还原**（验收标准 8）。
5. 全部取消勾选 → 地址栏为 `types=none`，右侧显示「当日暂无事项」类空态；刷新后仍是空态（不是全选）。
6. 回到本月/今天 → `month` / `date` 参数从地址栏消失（等于缺省值不写）。
7. 手改 `month=2026-13` → 自动回落到当月，不发非法请求。

- [ ] **Step 6: 提交**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
git add frontend/admin-web/src/pages/OpsCalendarPage.tsx
git commit -m "feat(admin): 经营日历的月份/日期/事件类型进 URL 并按来源返回"
```

---

## Task 7: 行内下钻链接统一记录来源

**Files:**
- Modify: `frontend/admin-web/src/components/TableActions.tsx`
- Modify: `frontend/admin-web/src/pages/DunningAutoPage.tsx`

**Interfaces:**
- Consumes: `currentPath`（既有 `@/lib/navigation`）
- Produces: `TableActions` 的 `Link` 自动携带 `state.from` —— 调用点不需要逐个传

**背景**：`TableActions` 用 `<Link to={action.to}>` 渲染链接，**不传 state**。因此
「项目分区管理」的「一物一档」与「催缴自动化」的 `/tasks` 返回时会走
`useBackNavigate` 的兜底路径（浏览器上一页 / 硬编码 fallback），而不是回到带筛选的原地。

- [ ] **Step 1: TableActions 自动带 `state.from`**

把 import 区：

```ts
import { Link } from 'react-router-dom';
```

改为：

```ts
import { Link, useLocation } from 'react-router-dom';
import { currentPath } from '@/lib/navigation';
```

在组件内（`useAuth` 附近）新增：

```ts
  const location = useLocation();
```

把链接渲染（约 94–102 行）：

```tsx
          <Link
            key={action.key}
            to={action.to}
            className={linkClassOf(action)}
            aria-label={action.label}
            tabIndex={0}
          >
```

改为：

```tsx
          <Link
            key={action.key}
            to={action.to}
            // 统一记录来源：调用点不必逐个传 state，返回时才能回到跳转前那一次的分页与筛选
            state={{ from: currentPath(location) }}
            className={linkClassOf(action)}
            aria-label={action.label}
            tabIndex={0}
          >
```

- [ ] **Step 2: 催缴自动化的合同链接**

把 import 区：

```ts
import { Link } from 'react-router-dom';
```

改为：

```ts
import { Link, useLocation } from 'react-router-dom';
import { currentPath } from '@/lib/navigation';
```

在组件内（`const [queue, setQueue] = ...` 之前）新增：

```ts
  const location = useLocation();
```

把（约 203–206 行）：

```tsx
                  <Link to={`/contracts/${v}`} className="text-[var(--ams-primary)]">
```

改为：

```tsx
                  <Link
                    to={`/contracts/${v}`}
                    state={{ from: currentPath(location) }}
                    className="text-[var(--ams-primary)]"
                  >
```

> `/dunning/records` 那一处（约 130 行）是跳到另一个**列表页**而非详情，语义上不该
> 「返回原页面」，保持不传 state。

- [ ] **Step 3: 构建、lint、格式化**

Run:

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm --filter admin-web build && pnpm lint && pnpm format:check
```

Expected: 全部退出码 0。

- [ ] **Step 4: 手工验收**

1. `/project-zones` 选中项目与分区、翻到资产第 2 页 → 点「一物一档」→ 返回 →
   回到带 `projectId` / `zoneId` / `assetPage` 的原地。
2. `/dunning/auto` → 在「催缴队列」里点某个合同的链接 → 合同详情页返回 →
   回到 `/dunning/auto`（而不是浏览器历史的更早一页）。
3. 未改造的列表页（`/approvals`、`/payments/pending-confirm`、`/reports`）行为不变（验收标准 9）。

- [ ] **Step 5: 提交**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
git add frontend/admin-web/src/components/TableActions.tsx frontend/admin-web/src/pages/DunningAutoPage.tsx
git commit -m "feat(admin): 行内下钻链接统一携带 state.from，返回跳转前的列表状态"
```

---

## 收尾：全量回归

- [ ] **Step 1: 纯逻辑断言**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
node scripts/test-list-query-params.mjs
```

Expected: 全部断言通过。

- [ ] **Step 2: CI 等价校验**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system/frontend
pnpm --filter admin-web build && pnpm lint && pnpm format:check && pnpm check:perm
```

Expected: 四条全部退出码 0。

- [ ] **Step 3: 确认没有引入依赖改动**

```bash
cd /Users/lixun/Desktop/projects/asset-management-system
git diff --stat HEAD~7 -- frontend/pnpm-lock.yaml frontend/admin-web/package.json frontend/package.json
```

Expected: `pnpm-lock.yaml` 与两个 `package.json` 的差异**只有** `frontend/package.json`
里新增的 `test:list-query` 一行（Task 1 Step 3）；不得出现新依赖。

- [ ] **Step 4: 逐条走完设计 §9 的 10 条验收标准**

按 `docs/superpowers/specs/2026-09-12-list-return-state-design.md` §9 逐条核对，
把每条的实际结果记录到进度台账。第 9 条（未改造页面行为不变）重点核对
`/approvals`、`/payments/pending-confirm`、`/reports`、`/system/roles`、
`/contract-templates`、`/system/menus`、`/system/dict`。
