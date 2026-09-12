# 前端「返回还原」——列表分页/筛选以 URL 为唯一真相 设计

**日期**：2026-09-12
**状态**：已确认，待实现
**范围应用**：仅 `frontend/admin-web`（不含 H5 / 小程序）
**关联设计**：
- `docs/superpowers/specs/2026-09-12-project-zone-management-design.md`（§5.4 已确立「选中态以 URL query 为准」的先例，本设计把它推广为全站列表约定）
- `docs/superpowers/specs/2026-09-12-record-forms-design.md` / `2026-09-12-menu-and-role-permission-design.md`（列表页与权限口径，本设计不改）

---

## 1. 背景与问题

`frontend/admin-web/src/lib/navigation.ts` 已有「按来源返回」的机制：

```ts
// 进入详情页时请用 navigate(to, { state: { from: currentPath(location) } }) 传入来源。
export const currentPath = (location) => `${location.pathname}${location.search ?? ''}`;
export function useBackNavigate(fallback) {
  // 优先 location.state.from → 浏览器历史上一页 → 兜底路径
}
```

详情页返回时用 `state.from` 跳回列表。**问题在于 `currentPath` 只带 `location.search`，而列表的分页/筛选根本不在 URL 上**：

| 页面 | 状态存放 | 返回后表现 |
|------|----------|-----------|
| `ResourcePage`（资产/项目/合同/账单/租户…… 全部 `RESOURCES` 共用） | `useState`：`page` / `pageSize` / `keyword` / `filters` | 回第 1 页、无关键字、无筛选 |
| `ProjectListPane`（项目分区管理左栏） | `useState`：`keyword` / `page` | 回第 1 页、无关键字 |
| `ZoneAssetPane`（项目分区管理右下资产表） | `useState`：`page` | 回第 1 页 |
| `ProjectDetailPage`（项目详情底部资产按分区/状态筛选） | `useState`：`zoneTab` / `statusFilter` / `assetView` | 分区与状态筛选丢失 |
| `AssetMapPage`（资产地图城市/状态筛选） | `useState`：`city` / `statusFilter` | 城市与状态筛选丢失 |
| `OpsCalendarPage`（运营日历月份/日期/类型筛选） | `useState`：`panelDate` / `selectedDate` / `types` | 月份、选中日期、类型勾选丢失 |

因为列表页与详情页是**不同的路由元素**，跳走时列表组件**整页卸载**；跳回时组件重新挂载，任何不在 URL 上的 state 全部归零。这与 `ProjectZonesPage` §5.4 已经论证过的结论完全同源，只是当时只解决了「选中项目/分区」一个场景。

此外，仍有若干**下钻跳转没有携带 `state.from`**，即使列表状态进了 URL 也回不到原地：

- `TableActions.tsx` 渲染的 `<Link to={action.to}>` **不带 `state`** —— 影响 `ZoneAssetPane` 的「一物一档」、`DunningAutoPage` 的「/tasks」。
- `ProjectDetailPage.tsx` → `/assets/{id}/dossier` 的手写 `<Link>`。
- `OpsCalendarPage.tsx` → `ev.linkPath` 的手写 `<Link>`。
- `DunningAutoPage.tsx` → `/contracts/{v}` 的手写 `<Link>`。

**目标**：任何「列表（或带筛选的看板）→ 详情/表单 → 返回」的路径，返回后还原上一次的**页码、每页条数、关键字、筛选条件、视图模式**；同时支持浏览器前进/后退与刷新；URL 可分享。

---

## 2. 已确认决策

| 项 | 决策 | 理由 |
|----|------|------|
| 范围 | **仅 `frontend/admin-web`** | H5 / 小程序无 `state.from` 约定，另案处理 |
| 覆盖广度 | **所有「会下钻跳转并返回」的列表页** | 用户确认 |
| 真相来源 | **URL query 为唯一真相** | 用户确认；可刷新保持、可分享，且与 `ProjectZonesPage` 既有约定一致 |
| 实现形态 | **共享 `useListQuery` hook**（方案 A1） | 5+ 处重复的拼装/校验/合并逻辑收敛为一处，避免各页漂移 |
| 内存 store / sessionStorage | **不采用** | 内存态丢失于刷新、不可分享；sessionStorage 引入第二真相来源且不可见 |
| 客户端分页且无下钻的页面 | **不改**（审批 / 收款确认 / 报表 / 系统角色 / 合同模板 / 菜单 / 字典等） | 没有「跳转后返回」路径，改它们只有刷新保持收益，收益不足 |
| `useBackNavigate` 的 push 语义 | **不改**（返回时 `navigate(from)` 仍 push） | 本设计聚焦状态还原；浏览器历史条目的 push/replace 语义是另一件事，避免顺手改坏既有行为 |

---

## 3. 现状盘点（改造清单）

### 3.1 需要 URL 化的状态

| 文件 | 现有 state（行号） | 改造后来源 |
|------|--------------------|-----------|
| `src/components/ResourcePage.tsx` | `page` / `pageSize` / `keyword` / `filters`（694–698）、`viewMode`（718） | `useListQuery({ filterKeys })` + `view` 参数 |
| `src/components/ProjectListPane.tsx` | `keyword`(33) / `query`(35) / `page`(36) | `useListQuery({ prefix: 'project' })`（`keyword` 仍保留本地草稿） |
| `src/components/ZoneAssetPane.tsx` | `page`(95) | `useListQuery({ prefix: 'asset' })` |
| `src/pages/ProjectDetailPage.tsx` | `zoneTab`(166) / `statusFilter`(167) / `assetView`(168) | `useUrlParam` × 3 |
| `src/pages/AssetMapPage.tsx` | `city`(79) / `statusFilter`(80) | `useUrlParam` × 2 |
| `src/pages/OpsCalendarPage.tsx` | `panelDate`(72) / `selectedDate`(73) / `types`(74) | `useUrlParam` × 3（`date` 用自定义 codec） |

### 3.2 需要补 `state.from` 的下钻跳转

| 文件 | 跳转 | 处理 |
|------|------|------|
| `src/components/TableActions.tsx` | 任意 `action.to` 的 `<Link>` | 组件内统一点 `state={{ from: currentPath(location) }}` |
| `src/pages/ProjectDetailPage.tsx` | `/assets/{id}/dossier`（682 行） | 补 `state` |
| `src/pages/OpsCalendarPage.tsx` | `ev.linkPath`（284 行） | 补 `state` |
| `src/pages/DunningAutoPage.tsx` | `/contracts/{v}`（204 行） | 补 `state` |

> `AssetMapPage.tsx`（302 行）与 `ResourcePage.tsx`（802/1037/1225/1271 行）已经带 `state.from`，无需改动；只是其 `currentPath` 会因本设计而带上列表 query。

---

## 4. 参数命名约定

**保留字（不可被业务筛选占用）**：`page`、`pageSize`、`keyword`。

| 页面 | URL 参数 |
|------|----------|
| `ResourcePage`（每条资源路由） | `page`、`pageSize`、`keyword`、`view`、筛选键**裸名**（如 `status=leased`、`contractId=12`） |
| `ProjectZonesPage` | `projectId`、`zoneId`（沿用现状） |
| `ProjectListPane` | `projectPage`、`projectKeyword` |
| `ZoneAssetPane` | `assetPage` |
| `ProjectDetailPage` | `zone`、`status`、`view`（`floor` \| `list`） |
| `AssetMapPage` | `city`、`status` |
| `OpsCalendarPage` | `month`（`YYYY-MM`）、`date`（`YYYY-MM-DD`）、`types`（逗号分隔） |

设计取舍：

- **`ResourcePage` 的筛选键用裸名**（不加前缀）：URL 可读、与 API 参数同形，例如 `/assets?page=2&status=leased&keyword=甲`。每个 `ResourcePage` 独占一条路由、无同级参数，不存在冲突；代价是筛选配置里不能出现保留字键名（见 §8 的守卫）。
- **嵌套在同一 URL 的列表加前缀**：`ProjectZonesPage` 一页里同时有「左栏项目列表」与「右下资产表」两套分页，且左栏还有关键字 —— 不加前缀必然互相覆盖。
  **前缀按驼峰拼接**：`prefix: 'asset'` 产出 `assetPage` / `assetPageSize` / `assetKeyword` / `asset<筛选键>`（筛选键首字母同样大写），
  而不是平铺的 `assetpage`。实现见 `listQueryParams.ts` 的 `buildKey`。
- **非列表的筛选量（项目详情/地图/日历）用语义化短名**，各自独占路由，同样不冲突。

---

## 5. 共享 hook 设计（`src/lib/listQuery.ts`，新建）

### 5.1 `useUrlParam` —— 单值 URL 参数

```
useUrlParam<T>(key, defaultValue, codec?):
  searchParams = useSearchParams()
  raw = searchParams.get(key)
  // ⚠️ 必须 memo：[raw, codec, defaultValue] 不变就不重新解析
  value = useMemo(
    () => raw == null ? defaultValue : codec ? codec.parse(raw) : (raw as T),
    [raw, codec, defaultValue])
  setValue(next):
    setSearchParams(prev =>
      next 等于 defaultValue（或空）→ delete(key)，否则 set(key, codec ? codec.serialize(next) : String(next))
    , { replace: true })
  return [value, setValue]
```

> **⚠️ `value` 必须 memo，且 `defaultValue` 必须是稳定引用。**
> 带 codec 的解析每次都返回**新对象**（`dayjs(...)`、`split(',')` 的数组）。若不 memo，
> 调用方把它放进 `useCallback`/`useMemo` 依赖时，每次渲染都会换新函数，再被
> `useEffect([loadSummary])` 这类 effect 捕获，就会变成
> 「拉取 → setState → 重渲染 → 再拉取」的**无限请求循环**（`OpsCalendarPage` 的
> `panelDate`/`selectedDate` 正是这种用法，是本设计最容易踩的一处）。
> 由此要求 `defaultValue` 为模块常量或 `useMemo` 值 —— 写成
> `useUrlParam('types', TYPE_OPTIONS.map(...))` 会让 memo 每次都失效。

- `codec` 用于非字符串量：`OpsCalendarPage` 的 `types: string[]`（逗号分隔）、`date`/`month`（dayjs 转换）。

> **⚠️ 返回值是三元组，第三项 `toWrite` 用于「一次交互改多个键」。**
> ```
> [value, setValue, toWrite] = useUrlParam(key, defaultValue, codec?)
>   setValue(next)        // 只改这一个键
>   toWrite(next)         // 把新值翻译成 { key, raw }，raw=null 表示删除该键
> writeParams = useUrlParams()      // 一次 setSearchParams 原子提交多个键
>   writeParams([monthWrite(day), dateWrite(day)])
> ```
> **为什么需要它**：react-router 的 `setSearchParams(fn)` 是把**本次渲染闭包里的
> `searchParams`** 交给 `fn` 求 `prev`（`react-router-dom/dist/index.js:1030-1036`），
> 所以同一事件里连着调两次 setter，两次都从同一份旧参数出发，**后一次覆盖前一次**，
> 先写的键会丢 —— 原设计以为「函数式更新会依次叠加」，实测源码是不成立的。
>
> 现实触发点：antd `Calendar` 跨月选中某天时会**同时**触发 `onPanelChange` 与 `onSelect`
> （`es/calendar/generateCalendar.js` 的 `triggerChange` 先 `triggerPanelChange` 再
> `onInternalSelect`）。让**最后**那次写入同时覆盖 `month` 与 `date`，结果就与触发次数无关。
> 这也即 §6.5 里 `OpsCalendarPage` 必须用 `writeParams` 而不是两个 setter 的原因。
>
> 注意 `useListQuery.patch` 是**一次**调用内改多个键，不受此影响；受影响的是
> 「连调两个不同 hook 的 setter」这种写法。
- 非法值由 `codec.parse` 自行回落到默认值，避免页面渲染崩溃。

> **⚠️ `setValue` 不是引用稳定的，且必须保持这样。**
> react-router 6.30 的 `useSearchParams` 内部是
> `setSearchParams = useCallback(…, [navigate, searchParams])`（已在
> `node_modules/…/react-router-dom/dist/index.js:1030-1036` 核对），所以 **URL 一变，
> setter 换新引用**，`useUrlParam` / `useListQuery` 的 setter 同理。
>
> 由此得到两条硬约束：
> 1. **不要把 setter 放进 effect 依赖数组里做「无条件写入」**
>    （`useEffect(() => setX(compute()), [setX])`）—— 每次 URL 变化都会重跑并再次写入，
>    直接死循环。
> 2. 正确的写法是**带守卫的自愈**：先判断「当前值确实非法」，再回落到缺省，于是第一次
>    写入后条件即不成立、effect 收敛（`ProjectDetailPage.tsx` 的两个自愈 effect 即此模式，
>    也是本设计唯一允许把 setter 放进依赖数组的地方）。
>
> 为什么不去「修」成稳定引用：`setSearchParams` 的函数式更新是靠**闭包里的 `searchParams`**
> 求 `prev` 的，用 ref 把它冻成稳定引用会让 `prev` 变成过期快照，
> **并发写入时会丢更新** —— 那是个真 bug，比多跑几次 effect 严重得多。

### 5.2 `useListQuery` —— 列表分页/筛选

```
useListQuery({ prefix = '', defaultPageSize = 10, filterKeys = [] }):
  searchParams = useSearchParams()

  readInt(key, fallback):                        // 与 ProjectZonesPage 的 toPositiveInt 同口径
    raw = searchParams.get(key)
    if !raw || !/^\d+$/.test(raw) || Number(raw) <= 0 → fallback
    else Number(raw)

  // 键名拼接：无前缀原样（page / status），有前缀驼峰（assetPage / asset<筛选键>）
  key(k) = prefix ? prefix + k[0].toUpperCase() + k.slice(1) : k

  page     = readInt(key('page'), 1)             // 正则而非 Number.isFinite：拒绝 1.5 / 1e3 / 0x10
  pageSize = readInt(key('pageSize'), defaultPageSize)
  keyword  = searchParams.get(key('keyword')) ?? ''

  // 只解释 filterKeys 列出的键；其余 URL 参数原样保留、不解释（见 §8）
  filters = useMemo(
    Object.fromEntries(filterKeys.map(k => [k, searchParams.get(key(k)) ?? '']).filter(([,v]) => v !== '')),
    [searchParams.toString(), prefix, filterKeys.join(',')]
  )

  // 底层写入口：一次内聚写入，其余参数原样保留
  //   partial.filters 里的每个键 k → 写 key(k)（值空则删除）
  //   partial.page / pageSize / keyword → 写 key('page') / key('pageSize') / key('keyword')
  //   缺省值不写：page === 1、pageSize === defaultPageSize、空串 → 删除对应键
  patch({ page, pageSize, keyword, filters }, { resetPage = false } = {}):
    setSearchParams(prev =>
      next = new URLSearchParams(prev)
      writes = { ...(page != null && { page }), ...(pageSize != null && { pageSize }),
                 ...(keyword != null && { keyword }), ...(filters ?? {}) }
      for [k, v] of writes:
        有值 → next.set(key(k), String(v))；空串/undefined → next.delete(key(k))
      if resetPage → next.delete(key('page'))
      return next
    , { replace: true })

  setPage(n)      = patch({ page: n })
  setPageSize(n)  = patch({ pageSize: n }, { resetPage: true })
  setKeyword(kw)  = patch({ keyword: kw }, { resetPage: true })
  setFilter(k, v) = patch({ filters: { ...filters, [k]: v } }, { resetPage: true })
  setFilters(nf)  = patch({ filters: nf }, { resetPage: true })

  return { page, pageSize, keyword, filters, patch,
           setPage, setPageSize, setKeyword, setFilter, setFilters }
```

关键语义：

1. **写入一律「合并 + replace」**：用函数式 `setSearchParams(prev => ...)`，只改动本列表负责的键，**绝不整包替换** —— 这是嵌套列表（`ProjectZonesPage`）能共存的前提。
2. **`replace: true`**：翻页/筛选不应把浏览器历史塞满；跳出去时列表的最后状态已在当前条目里，返回即命中（与 `ProjectZonesPage` §5.4 同口径）。
3. **空值即删除**：`keyword=''`、筛选取消后从 URL 移除，保持链接干净。
4. **关键字/筛选变化 → 页码归 1**（`resetPage`），与现有行为一致。
5. **`filters` 引用稳定**：以**筛选值本身**为依赖 `useMemo`，**不是**整串 `searchParams.toString()`。同一 URL 上承载的其它参数（`view`、`zoneId` 等）变化不该让 `filters` 换新引用 —— 否则调用方把 `filters` 放进拉取 effect 的依赖时，切一次视图就会多发一次请求（违反 §6.1「切视图不重新请求」）。

### 5.3 为什么把「拉取」改为 URL 驱动（`ResourcePage` 必做）

现在 `ResourcePage` 是「改 state → 显式调 `load(...)`」，URL 化后必须改为「`load` 依赖 URL 派生值 → `useEffect` 拉取」，否则：

- 用户在列表内**浏览器后退**（第 2 页 → 第 1 页）时组件不卸载、URL 变了却没有显式 `load`，表格会停在旧数据。
- 从详情页 `navigate(from)` 跳回时组件会重新挂载，旧写法碰巧能工作，但两种路径行为不一致本身就是隐患。

---

## 6. 逐页改造设计

### 6.1 `ResourcePage.tsx`

```
const { page, pageSize, keyword, filters, setPage, setPageSize, setKeyword, setFilters } =
  useListQuery({ filterKeys: [...config.filters, ...config.tagFilters].map(f => f.key) })

const [kwDraft, setKwDraft] = useState(keyword)        // 输入框草稿，避免每敲一个字都写 URL
useEffect(() => setKwDraft(keyword), [keyword])        // 前进/后退时把输入框同步回 URL 值

const seqRef = useRef(0)
const load = useCallback(async () => {
  const seq = ++seqRef.current
  setLoading(true)
  try {
    ...按 page/pageSize/keyword/filters + config.extraParams 拼请求...
    const raw = await api.get(...)
    if (seq !== seqRef.current) return                  // 丢弃过期响应，防快速翻页乱序覆盖
    setData(normalizePage(raw, page, pageSize))
    ...statsSource 同步拉取...
  } catch (e) {
    if (seq === seqRef.current) { setData(空页); message.error(...) }
  } finally {
    if (seq === seqRef.current) setLoading(false)
  }
}, [page, pageSize, keyword, filters, config])

const reload = () => setReloadToken(t => t + 1)
useEffect(() => { void load() }, [load, reloadToken])
```

- **删除**原 `useEffect([config.listPath])`（约 790 行）里的 `setPage(1)/setFilters({})/setKeyword('')/load(...)` 重置块：路由切换时 URL 本身不带 query，`load` identity 变化会自然触发一次首拉。
- 查询按钮：`setKeyword(kwDraft); if (kwDraft === keyword) reload()`（关键字没变时靠令牌保证重拉）。
- `setFilterValue` 保留级联清空逻辑，末尾改为 `setFilters(nf)`（页码自动归 1）。
- 分页 `onChange`：`setPage(p); setPageSize(size)` 合成一次 → 用底层 `patch({ page: p, pageSize: size })`，避免两次写 URL。
- 视图切换：`viewMode` 改由 `useUrlParam('view', 'list')` 承载；**不进入 `load` 依赖**，切换视图不重新请求。
- 增 / 删 / 改 / 导入成功后：`reload()`（如需回第 1 页则先 `setPage(1)`，与令牌同批渲染，只发一次）。
- 跳转（详情/编辑/新增）继续用 `currentPath(location)`，此时它已包含全部筛选与页码。

### 6.2 `ProjectListPane.tsx` + `ZoneAssetPane.tsx` + `ProjectZonesPage.tsx`

- `ProjectListPane`：`keyword`/`query`/`page` → `useListQuery({ prefix: 'project', defaultPageSize: 20 })`；输入框草稿与本组件现有「`keyword` 输入 / `query` 已提交」的分离保持一致，`handleSearch` 改为 `setKeyword(kwDraft); if (kwDraft === query) reload()`。
- `ZoneAssetPane`：`page` → `useListQuery({ prefix: 'asset' })`；`loadAssets` 依赖 `[projectId, zoneId, page, canViewLedger]`，`useEffect([loadAssets, assetsReloadToken])`。删除现有「上下文变化 → `setPage(1)`」的 effect：切项目/分区时**由父级原子清掉 `assetPage`**（见下），子组件一次渲染就以第 1 页拉取，不会出现「先旧页再补第 1 页」的双请求。
- `ProjectZonesPage`：`selectProject` / `selectZone` 从**整包对象替换**改为**函数式合并**，只动自己负责的键，并清除右栏分页：

```
selectProject(id):
  setSearchParams(prev => {
    next = copy(prev)
    next.set('projectId', id)
    next.delete('zoneId')          // 切项目 → 回「全部分区」
    next.delete('assetPage')       // 右栏资产分页归 1
    // 保留 projectPage / projectKeyword：左栏搜索不因换项目而白清
    return next
  }, { replace: true })

selectZone(id):
  next.set('projectId', projectId)
  id == null ? next.delete('zoneId') : next.set('zoneId', id)
  next.delete('assetPage')
```

> 现状的整包替换会把左栏 `projectPage` / `projectKeyword` 一起抹掉（今天因为它们是本地 state 所以看不出来）。改合并后，左栏搜索在切项目时保留。

- `ZoneAssetPane` 的「刷新」：`setPage(1)` + `setAssetsReloadToken(t => t + 1)`（若本就在第 1 页，令牌保证重拉），维持**分区联动设计**（`2026-09-12-project-zone-management-design.md`）§5.2 表格第 7 行「刷新归第 1 页」。

> **跨设计契约（易漏）**：分区联动设计 §5.2 的表格里，第 3/4/5 行（新增或编辑分区、删除分区、增删改资产）都要求「资产区分页归 1 + 重拉」。本设计把资产分页搬进 URL 后，这些行**不会自动满足** —— 原先它们靠「上下文变化 effect 里的 `setPage(1)`」顺带实现，而 §6.2 又要求删掉那个 effect。因此改完后必须由 `reloadAssets()` 自己归 1（`setPage(1)` + 令牌），否则新增/编辑/删除分区与删除资产都会停在第 2 页上，删除末页最后一条还会停在空页。`rows 1/2`（切项目、切分区）仍由父级原子删 `assetPage` 负责。

### 6.3 `ProjectDetailPage.tsx`

- `zoneTab` → `useUrlParam('zone', 'all')`；`statusFilter` → `useUrlParam('status', '')`；`assetView` → `useUrlParam('view', 'floor')`。
- 三者均为**客户端筛选**（资产一次拉 500 条在内存里筛），URL 变化只触发 `useMemo` 重算，**不发请求**。
- 682 行 `/assets/{id}/dossier` 的 `<Link>` 补 `state={{ from: currentPath(location) }}`。
- 校验：`zone` 值必须存在于 `overview.zones`，否则回落 `'all'`（避免手改 URL 后筛选出空表却看不出原因）。

### 6.4 `AssetMapPage.tsx`

- `city` → `useUrlParam('city')`；`statusFilter` → `useUrlParam('status')`。均为客户端筛选。
- `selectedId` **继续留在本地 state**：它是「当前查看哪个点」的瞬态选择，返回后重新点选即可，且它的默认值 `filtered[0]` 依赖数据加载，放进 URL 反而要处理「点位不存在」的自愈。若 URL 无 `selectedId`，行为与今天一致。

### 6.5 `OpsCalendarPage.tsx`

- `panelDate` → `useUrlParam('month', dayjs(), monthCodec)`；`selectedDate` → `useUrlParam('date', dayjs(), dateCodec)`；`types` → `useUrlParam('types', 全部类型, csvCodec)`。
  - 三者都必须是**稳定引用**的默认值：`today` 用 `useMemo(() => dayjs(), [])`、全部类型用模块级
    常量 `ALL_TYPES`（§5.1 的 memo 契约）。写成 `TYPE_OPTIONS.map(...)` 内联会把 memo 废掉。
- `loadSummary` 已经依赖 `panelDate`/`types`，URL 化后仅来源变化，拉取时机不变。
  - **`month` 与 `date` 必须用 `writeParams` 一次写完**（§5.1）：antd `Calendar` 跨月选中某天时
    会同时触发 `onPanelChange` 与 `onSelect`，两个 handler 各写一次 URL 的话后写的会覆盖先写的。
    让 `onSelect` 的处理函数一次写 `month` + `date`（两者都取自被选中的那天），
    `onPanelChange`（只切面板、不选日期）只写 `month`。
- 284 行 `ev.linkPath` 的 `<Link>` 补 `state={{ from: currentPath(location) }}`。
- 校验：`month`/`date` 解析失败回落当天；`types` 过滤掉未知类型，全部未知则回落「全选」。

### 6.6 `DunningAutoPage.tsx`

- 204 行 `/contracts/{v}` 的 `<Link>` 补 `state={{ from: currentPath(location) }}`。
- 该页没有分页/筛选 state（两张表都是客户端固定分页），**不引入 URL 参数**；仅补齐返回来源。

### 6.7 `TableActions.tsx`

```
const location = useLocation()
...
<Link to={action.to} state={{ from: currentPath(location) }}>...</Link>
```

- `TableActions` 的所有 `to` 链接都是「行内下钻」（`ZoneAssetPane` 一物一档、`DunningAutoPage` /tasks），统一记录来源是正确语义，调用点不必逐个传。
- 只有 `onClick` 的按钮项不受影响（它们本就在弹窗/接口内处理，不产生跳转）。

---

## 7. 数据流（一条完整路径）

```
用户在 /assets?page=3&status=leased&keyword=甲
  → 点行「编辑」：navigate('/assets/12/edit', { state: { from: '/assets?page=3&status=leased&keyword=甲' } })
  → 编辑页保存成功：goBack() = useBackNavigate('/assets')
      · location.state.from 存在且 ≠ 当前页 → navigate('/assets?page=3&status=leased&keyword=甲')
  → ResourcePage 重新挂载，useListQuery 从 URL 读出 page=3 / status=leased / keyword=甲
  → load() 以该条件拉取 → 表格、筛选下拉、输入框、分页器全部还原
  → 直接刷新浏览器：URL 不变，还原结果一致
```

---

## 8. 边界与错误处理

| 场景 | 处理 |
|------|------|
| `page=1.5` / `page=0` / `page=abc` | `readInt` 用 `/^\d+$/` 拒绝，回落默认页；不产生非法请求（对齐 `ProjectZonesPage` 的 `toPositiveInt` 口径） |
| 空串参数（`?keyword=`、`?status=`） | 视为缺省；写入时从 URL 删除 |
| 未列入 `filterKeys` 的 URL 参数 | **原样保留、不解释、不发送**（防止外部深链参数被误当筛选拼进接口） |
| 筛选键名撞保留字（`page`/`pageSize`/`keyword`） | `useListQuery` 在开发态对 `filterKeys` 做一次断言（`console.warn`），提示改名；生产不崩 |
| 快速翻页导致的响应乱序 | `ResourcePage` 用 `seqRef` 序号守卫，只接受最后一次请求的响应 |
| 关键字输入框每敲一字请求 | 输入框保留本地草稿 `kwDraft`，仅回车/点「查询」才写入 URL；URL 因前进/后退变化时反向同步草稿 |
| 嵌套列表互相覆盖参数 | 所有写入走函数式合并；`ProjectZonesPage` 只清自己负责的键 |
| 手改 URL 传入不存在的 `zone` | `ProjectDetailPage` 回落 `'all'`，UI 与数据一致 |
| 服务端返回空列表 | 维持现有空态，不因 URL 参数而特别处理 |

---

## 9. 验收标准

1. 资产台账筛「租控状态 + 关键字 → 翻到第 2 页」→ 点行「编辑」→ 保存返回 → 页码、筛选、关键字**全部还原**。
2. 同上路径点「一物一档」→ 从档案页返回 → 同样还原。
3. 上述任一还原后的 URL 直接刷新浏览器 → 条件与页码仍在，数据一致。
4. **历史条目语义（`replace: true`，见 §5.4）**：在列表内翻页/改筛选**不产生**新的历史条目，
   列表当前条目被原地替换为最新条件。因此：
   - 从列表进入详情后按浏览器**后退** → 回到列表，且条件正是跳转前那一次（`/assets?page=2&...`）；
   - 在列表内翻页后按浏览器**后退** → **退出列表**（回到进入列表前的页面），而不是逐页回退。
   这两条都要成立，且不出现「URL 变了、数据没变」。
5. 项目分区管理：左栏输入关键字并翻页 → 选中一个项目 → **左栏关键字与页码保留**、右下资产表归第 1 页；进资产表单返回 → 左栏与右栏各自还原。
6. 项目详情：选「某分区 + 某租控状态 + 列表视图」→ 进一物一档 → 返回 → 三者还原。
7. 资产地图：选城市 + 状态 → 进档案 → 返回 → 两者还原。
8. 运营日历：切到上个月、勾选部分类型 → 点某事件的「处理」→ 返回 → 月份与类型勾选还原。
9. 未改造页面（审批 / 收款确认 / 报表 / 系统角色 / 合同模板 / 菜单 / 字典）行为不变。
10. `pnpm --filter admin-web build`、`pnpm lint`、`pnpm format:check` 全绿。

---

## 10. 明确不做

- H5（`h5-tenant` / `h5-worker`）与两个小程序。
- 无下钻跳转的客户端分页页（审批、收款确认、报表、系统角色成员表、合同模板、菜单、字典）。
- `useBackNavigate` 的 push/replace 语义调整、浏览器历史条目清理策略。
- 后端接口与 URL 参数的双向同步（列表 URL 参数仍只由前端消费）。
- 列表缓存/预取（每次进入都是真实请求，与现状一致）。
