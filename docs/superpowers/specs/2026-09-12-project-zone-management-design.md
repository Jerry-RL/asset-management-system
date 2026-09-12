# 项目分区管理页 设计

**日期**：2026-09-12
**状态**：已确认，待实现
**范围应用**：`backend`（仅 1 个迁移）+ `frontend/admin-web`
**关联需求**：`docs/需求规格说明书.md` FR-AST-001（项目下可关联多分区、多资产）
**关联设计**：
- `docs/superpowers/specs/2026-09-12-project-list-zone-expand-design.md`（项目列表展开行内的分区维护；本页与之操作同一批数据、同一批接口）
- `docs/superpowers/specs/2026-09-12-menu-and-role-permission-design.md`（菜单/权限口径）
- `docs/superpowers/specs/2026-09-12-record-forms-design.md`（分区后续记录；本页不涉及其读写）

---

## 1. 背景与问题

「分区」目前只能从项目侧进入：项目列表展开行（`ProjectZonesPanel`）或项目新增/编辑向导第二步。分区作为一个需要独立盘点的维度，缺一个**以分区为中心**的入口 —— 能选到某个分区，并直接看到、维护该分区下的资产。

| 维度 | 现状 | 问题 |
|------|------|------|
| 分区入口 | 项目列表展开行 / 项目编辑向导第二步 | 都是「先找到项目」，无法按分区视角工作 |
| 分区下资产 | 项目详情页底部可按分区筛选（只读，卡片/列表两种视图） | 能看到，但**不能在分区语境下增删改** |
| 资产增删改 | 资产台账（`/assets`）或资产独立表单页 | 必须先在表单里重新选一遍项目与分区 |
| 侧栏菜单 | 无「项目分区管理」 | 分区不是一个可直达的功能域 |

**目标**：新增顶级侧栏页「项目分区管理」（`/project-zones`）：左侧选项目、右侧上方面板用 Tab 切换分区、下方是该分区的资产表；分区与资产的增删改全部复用既有接口与既有表单页。

---

## 2. 已确认决策

| 项 | 决策 | 理由 |
|----|------|------|
| 入口形态 | **顶级侧栏菜单**「项目分区管理」，路径 `/project-zones` | 用户确认；分区成为可直达的功能域 |
| 页面布局 | **左右布局：左侧项目列表，右侧资产；右侧上方是分区 Tab 栏** | 用户确认；「项目 → 分区 → 资产」在一屏内三层可见，切换分区不产生页面跳转 |
| 分区 CRUD 入口 | **Tab 栏右侧放「新增分区」「编辑」「删除」**，作用于当前选中的 Tab | 用户要求 Tab 只承担切换；CRUD 与切换同处一行，无需二级入口或隐藏菜单 |
| 「全部分区」Tab | **保留，作为第一个 Tab** | `GET /assets?projectId=` 本就不带 `zoneId` 即可，**零新增接口**；跨分区排查/盘点时不必逐个 Tab 点 |
| 分区内资产的增删改 | **复用 `AssetFormPage`**（`/assets/create`、`/assets/:id/edit`），用 query 参数预填并锁定项目+分区 | 用户确认；完整表单的字典级联、图片上传、乐观锁版本号全部免费复用，不另造精简弹窗 |
| 接口 | **零新增后端接口**，全部复用既有 | 见 §2.1 |
| 页面权限 | 页面级 `asset.projectZone:view`（镜像推导）+ 动作级复用既有已强制码 | 见 §4 |
| 新菜单可见性 | **V47 向所有非超管角色回填 `asset.projectZone:view`** | 用户确认；与 V45 §5.1「导航类菜单回填 view 以保持可见性」同口径，避免新功能上线即不可见 |
| 「未划分区」资产桶 | **本期不做** | 用户确认；`zoneId IS NULL` 的资产由「全部分区」Tab 覆盖（它不过滤分区），不额外加参数 |

### 2.1 为什么复用接口而不是新建 `/project-zones/*`

本仓已经为「同一语义两条写入路径」付过代价：`replaceZones`（项目 PUT 全量替换）与 `deleteProjectZone`（分区级 DELETE）曾对「分区能不能删」给出不同判定，最后靠抽出 `assertZoneRemovable` 这个唯一判定点才收敛（见 record-forms 设计 §7.3）。

新增 `POST/PUT/DELETE /project-zones/*` 会把这套校验（归属断言、有资产拒绝、有后续记录拒绝、软删过滤）复制第二份，必然再次漂移。因此本设计坚持：

- 分区增删改 → `POST /projects/{id}/zones`、`PUT/DELETE /projects/{id}/zones/{zoneId}`
- 分区资产读写 → `GET/POST /assets`、`PUT/DELETE /assets/{id}`

代价是页面守卫码与动作判定码不同源（见 §4），这个代价被显式接受并在 §8 逐条兜底。

---

## 3. 路由与菜单

### 3.1 菜单种子（`V47__project_zone_menu.sql`）

```sql
INSERT INTO menu (code, name, menu_type, path, icon, sort, parent_id)
SELECT 'asset.projectZone', '项目分区管理', 'menu', '/project-zones', NULL, 15, d.id
FROM menu d WHERE d.code = 'asset'
ON CONFLICT (code) DO NOTHING;

-- 回填 view：本菜单在 V45 之后新增，V45 §5.1 的回填只覆盖了当时的菜单行。
-- 不回填则除 super_admin 外所有角色都看不到入口 —— 新功能表现为「没做出来」。
-- 与 V45 §5.1 同口径：只回填 view、排除 super_admin、写动作一律不回填。
INSERT INTO role_permission (role_id, menu_id, menu_code, action)
SELECT r.id, m.id, m.code, 'view'
FROM role r
CROSS JOIN menu m
WHERE r.code <> 'super_admin'
  AND m.code = 'asset.projectZone'
ON CONFLICT DO NOTHING;
```

- 归属目录 `asset`（资产台账），`sort = 15` → 排在「项目管理」(10) 与「资产台账」(20) 之间。
- `icon` 留空：与 V45 口径一致（「图标只给目录：菜单留空，侧边栏回退到前端路由注册表的 path 图标」）。
- 菜单行数由 64 → 65。V45 迁移里的「64 个」是**历史注释，不改**；`routeRegistry.ts` 的对应注释需同步为 65。

### 3.2 前端注册清单（6 处，缺一即静默失效）

| # | 文件 | 变更 | 缺了的后果 |
|---|------|------|-----------|
| 1 | `App.tsx` | `<Route path="project-zones" element={<ProjectZonesPage />} />` | 点菜单落回首页 |
| 2 | `lib/routeRegistry.ts` | `STANDALONE_ROUTES` 加 `/project-zones` | 侧栏过滤掉该入口（DB 行被当脏数据忽略） |
| 3 | `lib/pathToCode.ts` | `'/project-zones': 'asset.projectZone'` | `canByPath` 恒真 → 页面级守卫静默失效；降级菜单缺少该入口 |
| 4 | `pages/modules.tsx` | `MENU` 的「资产台账」组加菜单项 | 接口失败时的降级静态菜单缺少该入口 |
| 5 | `lib/menuIcons.tsx` | `PATH_ICONS['/project-zones'] = <PartitionOutlined />` | 回退成通用图标（不报错，只是不好看） |
| 6 | `V47__project_zone_menu.sql` | 菜单行 + view 回填 | 菜单不存在 → `PermissionRegistry` 启动失败/接口 403 |

`scripts/check-perm-invariants.mjs` 的第 3 条（镜像完整性）会在第 3 项遗漏时硬失败，这是本清单的自动化兜底。

---

## 4. 权限模型

| 操作 | 判定码 | 判定方式 | 对应接口 |
|------|--------|----------|----------|
| 进入页面 | `asset.projectZone:view` | `AdminLayout` 的 `<RequirePerm>`，由 path 经镜像推导 | — |
| 左侧项目列表 | `asset.project:view` | 左栏是否请求 | `GET /projects` |
| 分区 Tab 栏 | `asset.project:view` | Tab 栏是否请求 | `GET /projects/{id}/zones` |
| 新增/编辑/删除分区 | `asset.project:update` | `usePerm('asset.project','update')` | `POST/PUT/DELETE .../zones` |
| 右侧资产列表 | `asset.ledger:view` | 右栏是否请求 | `GET /assets?projectId&zoneId` |
| 新增/编辑/删除资产 | `asset.ledger:create/update/delete` | `usePerm('asset.ledger', action)` | `/assets` 写接口 |
| 一物一档 | `asset.ledger:view` | 链接显隐 | `GET /assets/{id}/dossier` |

### 4.1 为什么页面里不写 `perm="asset.projectZone:view"`

`check-perm-invariants.mjs` 强制「前端 `perm` 声明 ⊆ 后端 `@RequiresPerm` 集合」。`asset.projectZone` 只作为菜单码存在，**没有任何后端注解**会去强制它（页面守卫在 `AdminLayout` 里由镜像推导），因此一旦在 JSX 里写 `perm="asset.projectZone:view"`，脚本会直接硬失败。

结论：
- 页面级守卫**不写任何 `perm` 声明**，交给 `<RequirePerm>`（无 `code` 参数 → 从当前 path 推导）。
- 动作级守卫只用**后端已强制**的码，且优先用 `usePerm(code, action)` 函数式判定（脚本只解析 `perm:` / `perm="` 两种字面量，函数式调用天然不参与该校验）。
- 后端**不新增任何 `@RequiresPerm`**，`@RequiresPerm` 集合不变 → 脚本第 1、2、2b 条检查全部保持通过。

### 4.2 跨模块缺权的表现（必须逐条兜底）

本页是跨模块控制台，一个角色可能只有其中一部分权限。每种组合都有明确产物，不允许出现空白页：

| 缺的权限 | 表现 |
|----------|------|
| `asset.project:view` | 左栏显示「无项目查看权限」提示 + Tab 栏隐藏；**不发** `/projects`、`/projects/{id}/zones` 请求 |
| `asset.project:update` | Tab 栏的「新增分区」「编辑」「删除」整块隐藏；分区 Tab 仍可切换 |
| `asset.ledger:view` | 下方资产区显示「无资产查看权限」，**不发出** `/assets` 请求 |
| `asset.ledger:create` | 资产区「新增资产」隐藏 |
| `asset.ledger:update` | 行内「编辑」隐藏 |
| `asset.ledger:delete` | 行内「删除」隐藏 |

---

## 5. 交互设计

### 5.1 布局

```
┌──────────────────┬─────────────────────────────────────────────────────┐
│ 项目      [搜索] │  [全部分区] [A区 12] [B区 8] [C区 0]  [+新增分区][编辑][删除] │
│ ──────────────── │ ─────────────────────────────────────────────────── │
│ ▸ 项目A  128宗   │  A区 · 12 宗 · 3,200.00 ㎡            [+ 新增资产]     │
│   项目B   64宗   │ ┌ 资产编号 名称 类型 楼层 面积 租控状态 操作 ┐        │
│   项目C   20宗   │ │  ...                    编辑 / 一物一档 / 删除 │      │
│                  │ └ 分页 ──────────────────────────────────────┘      │
│  [‹ 1 2 3 ›]     │                                                     │
└──────────────────┴─────────────────────────────────────────────────────┘
  左侧：项目列表        右侧上方：分区 Tab 栏        右侧下方：该分区资产
```

**左侧（项目）** —— `lg:w-[280px]`，`flex-col lg:flex-row`
- 关键字搜索框（回车/按钮触发，`GET /projects?keyword=`）+ 项目列表 + 分页（`pageSize=20`）。
- 每行：项目名称 + `资产宗数` + `资产面积(㎡)`（`pageProjects` 已聚合好，零额外请求）；选中项高亮。
- 项目列表接口按 `id desc` 排序，分页解决数量问题（不拉 200 条一次性塞进侧栏）。

**右侧上方（分区 Tab 栏）** —— antd `Tabs`
- 第一个 Tab 固定为「全部分区」（不过滤 `zoneId`）；其余 Tab 来自 `GET /projects/{id}/zones`。
- Tab 文案：`分区名称` + 资产数徽标（`assetCount` 由分区接口直接返回，零额外请求）。
- `tabBarExtraContent`：`[+ 新增分区]` `[编辑]` `[删除]`，作用于当前选中 Tab；选中「全部分区」时后两者禁用（分区本身不存在，不是权限问题）。

**右侧下方（资产区）**
- 标题行：`{当前分区名} · 共 N 宗`，其中 `N` 取资产接口返回的 `PageResult.total`（**精确总数，不受分页影响**）；选中具体分区时追加 `· 合计 X ㎡`（取分区接口的 `assetArea`）。
  - 「全部分区」Tab **不显示面积**：全量面积需要额外一次请求或依赖可能过期的项目行聚合值，而当前页求和是错的（只有一页）。不显示比显示一个错的数字好。
- 资产表格：`资产编号 / 名称 / 资产类型 / 分区楼层 / 资产面积(㎡) / 租控状态 / 操作`；操作 = `编辑 / 一物一档 / 删除`；分页 `pageSize=10`。
- 「全部分区」Tab 下额外显示「分区」列（其他 Tab 下该列恒为同一分区，隐藏以免噪声）。

### 5.2 联动刷新（联通刷新数据的唯一契约）

| # | 触发 | 分区 Tab 栏 | 资产区 |
|---|------|------------|--------|
| 1 | 左栏切换项目 | 重拉 `GET /projects/{id}/zones`；**选中 Tab 重置为「全部分区」** | 分页归 1，重拉 |
| 2 | 切换分区 Tab | 不请求 | 分页归 1，重拉 |
| 3 | 新增 / 编辑分区 | 重拉 | 分页归 1，重拉 |
| 4 | 删除分区 | 重拉；若删的是当前 Tab → 回到「全部分区」 | 分页归 1，重拉 |
| 5 | 新增 / 编辑 / 删除资产 | 返回本页时整页重新挂载，天然重拉（见 §6.6） | 同左 |
| 6 | 左栏搜索 / 翻页 | 不请求（项目变了才重置） | 不请求（项目未变） |
| 7 | 点「刷新」 | 重拉 | 分页归 1，重拉 |

**第 5 行为什么也要重拉分区**：`assetCount` / `assetArea` 是 Tab 上的展示值，而它们由后端按分区实时汇总。新增资产（+1 宗、+面积）、删除资产（-1 宗）、编辑资产（改面积）都会让 Tab 上的数字过期 —— 只刷资产表会让「Tab 里的 12 宗」和「表里的 12 行」在后续操作后对不上。这是本页最容易漏掉的一处联动。由于资产的写操作发生在独立表单页（§6.7），本页在返回时会**整页重新挂载**，所以这条是自动满足的，不需要额外回调。

**不做缓存失效推断**：每次重拉都是真实请求（与 `ProjectZonesPanel` 口径一致）。分区数量与资产数量都受项目规模约束，不做「脏标记 + 命中判断」这套额外状态。

### 5.3 空态与边界

| 场景 | 表现 |
|------|------|
| 未选项目 | 右侧 Tab 栏与资产区都是引导空态「请先在左侧选择项目」；**不发**分区/资产请求 |
| 左栏项目列表为空 | 左栏空态；右侧保持引导空态（不发上层请求） |
| 项目无分区 | Tab 栏只有「全部分区」，下方提示「该项目暂无分区」+「新增分区」入口 |
| 当前分区无资产 | 资产区空态「该分区暂无资产」+「新增资产」入口 |
| 切项目后原分区不存在 | Tab 重置为「全部分区」（第 1 条已覆盖，不做「找最接近的分区」这类猜测） |

### 5.4 跳转与返回

- 「新增分区」「编辑分区」→ `ZoneFormModal` 弹窗表单（名称必填 / 编码 / 排序 / 备注）。
- 「删除当前分区」→ `confirmDelete` 二次确认。
- 「新增资产」→ `/assets/create?projectId={id}&zoneId={id}&lockScope=1`，带 `state: { from: currentPath(location) }`。
  - 在「全部分区」Tab 下不传 `zoneId`（新增资产的归属留空，由使用者在表单里选）。
- 「编辑资产」→ `/assets/{assetId}/edit?lockScope=1`（归属锁定，见 §6.6），同样带 `state.from`。
- 「一物一档」→ `/assets/{assetId}/dossier`。
- **选中状态以 URL query 为准**：`/project-zones?projectId=1&zoneId=2`，`zoneId` 缺省即「全部分区」。选择项目/切换 Tab 时用 `setSearchParams(..., { replace: true })` 写回（`replace` 避免 Tab 切换把浏览器历史塞满）。
  - 这不仅是「好看」：从本页跳去资产表单页再返回时 `ProjectZonesPage` 会**整页重新挂载**（不同路由元素），组件内部 state 全部丢失。选中项必须落在 URL 上，否则返回后项目与分区都会回到未选中态，验收标准 9 无法成立。
  - 附带收益：本页可刷新保持、可复制链接分享（与 `currentPath` 带 query 的既有返回机制天然对齐）。

---

## 6. 前端设计

### 6.1 新文件与改动清单

| 文件 | 变更 | 说明 |
|------|------|------|
| `src/pages/ProjectZonesPage.tsx` | 新建 | 「项目分区管理」页主体（左项目 / 右 Tab + 资产表） |
| `src/components/ProjectListPane.tsx` | 新建 | 左栏项目列表（搜索 + 列表 + 分页） |
| `src/components/ZoneAssetPane.tsx` | 新建 | 右侧「分区 Tab 栏 + 资产表」 |
| `src/lib/projectZones.ts` | 新建 | `ProjectZone` 类型 + `useProjectZones` 钩子（分区读写逻辑的单一来源） |
| `src/components/ZoneFormModal.tsx` | 新建 | 分区新增/编辑弹窗（两个入口共用） |
| `src/components/ProjectZonesPanel.tsx` | 修改 | 改为消费共享类型与钩子；呈现形态（表格）不变 |
| `src/pages/ProjectFormPage.tsx` | 修改 | 删除本地 `ProjectZone` 副本，改用 `lib/projectZones.ts`（纯类型收敛） |
| `src/pages/AssetFormPage.tsx` | 修改 | 支持 `?projectId&zoneId&lockScope`；保存成功后按 `state.from` 返回 |
| `src/App.tsx` | 修改 | 新增路由（§3.2 #1） |
| `src/lib/routeRegistry.ts` | 修改 | `STANDALONE_ROUTES` + 注释里的菜单计数 64 → 65 |
| `src/lib/pathToCode.ts` | 修改 | 镜像新增 `/project-zones` |
| `src/pages/modules.tsx` | 修改 | `MENU` 新增菜单项 |
| `src/lib/menuIcons.tsx` | 修改 | `PATH_ICONS` 新增图标 |

拆成三个文件而不是一个巨大页面：左栏、右侧 Tab+资产、分区读写逻辑各自只有一件事，单文件各自可读；`ProjectZonesPage.tsx` 只负责持有 `projectId` / `zoneId` 与 §5.2 的联动契约。

### 6.2 `lib/projectZones.ts`（消除重复的关键）

`ProjectZonesPanel` 当前内含「加载 / 新增 / 编辑 / 删除」全套逻辑。新页面若重写一遍，两处必然漂移（排序缺省、报错透传、只读字段口径都可能改一边漏一边）。因此把**逻辑**抽出来，**视图**仍各写各的：

```ts
export interface ProjectZone {
  id?: number;
  projectId?: number;
  name: string;
  code?: string;
  sort?: number;
  remark?: string;
  /** 只读：该分区下资产面积合计(㎡) */
  assetArea?: number;
  /** 只读：该分区下资产数量 */
  assetCount?: number;
}

export interface UseProjectZonesResult {
  zones: ProjectZone[];
  loading: boolean;
  loadFailed: boolean;
  /** 重新拉取；失败置 loadFailed，不抛 */
  reload: () => Promise<void>;
  /** 无 id → POST，有 id → PUT；成功返回 true 并已 reload，失败抛异常由调用方提示 */
  save: (zone: ProjectZone) => Promise<boolean>;
  /** 删除；后端拒绝时抛异常（原因必须原样透出） */
  remove: (zone: ProjectZone) => Promise<void>;
}

/** projectId 为空时不发请求，返回空列表 */
export function useProjectZones(projectId: number | null | undefined): UseProjectZonesResult;
```

- 类型归属：`ProjectZone` 目前有**两份重复定义** —— `ProjectZonesPanel.tsx`（含 `projectId`）与 `ProjectFormPage.tsx`（不含）。三者（新增的本页在内）共用同一批分区接口，字段口径必须一致。故把 `ProjectZone` 定为 `lib/projectZones.ts` 的**唯一定义**（`projectId` 声明为可选，向导里的新分区草稿同样适用），另外两处改为 import，删除本地副本。这是纯类型收敛，无运行时行为变化。
- `reload()` 由 §5.2 第 3、4、5、7 条调用；调用方在 `await save(...)` 之后不需要再手动 `reload`（钩子内部已做），避免重复请求。
- 加载失败**不弹全局 `message`**（与现状一致）：面板内显示错误态 + 重试即可。

### 6.3 `components/ZoneFormModal.tsx`

```tsx
export function ZoneFormModal({
  editing,     // null=关闭；{name:''}=新增；{id,...}=编辑
  submitting,
  onCancel,
  onSubmit,    // (values: ProjectZone) => void，由 useProjectZones().save 驱动
}: { ... })
```

- 字段：分区名称（必填，trim 后非空）、分区编码、排序、备注；资产面积/资产数**不出现在表单**（只读、由后端汇总）。
- 排序的 extra 提示按新增/编辑区分：编辑「留空表示保持原排序」，新增「留空表示追加到末尾」（沿用现状）。
- `forceRender` 常驻挂载，`openEditor` 里的 `setFieldsValue` 不因未连接而告警（沿用现状）。

### 6.4 `components/ProjectListPane.tsx`

```tsx
export function ProjectListPane({
  selectedId,
  onSelect,          // (projectId: number) => void
  canView,           // asset.project:view
}: { ... })
```

- 自持 `keyword` / `page` / `pageSize=20` / `rows` / `loading` / `loadFailed`；对外只暴露「当前选中 id」与「选中回调」，**不把项目列表状态提升到页面** —— 否则 `ProjectZonesPage` 要同时管理两套分页与错误态。
- `GET /projects?page=&pageSize=&keyword=`。
- 数据形态：`GET /projects` 确定返回 `PageResult`，按 `PageResult<ProjectRow>` 取值并以 `raw?.list ?? []` 兜底。
- 行内容：`name` + `资产宗数` + `资产面积(㎡)`（均来自 `pageProjects` 的聚合，零额外请求）。
- 选中态：`selectedId` 来自 URL（§6.6）。若它不在当前页（从链接直达或刷新后落在别的分页），该页不显示高亮 —— **不做**「自动跳到选中项所在页」这类猜测，右侧内容依旧正确（右侧只依赖 `projectId`，不依赖左栏是否高亮）。
- `canView = false` 时不发请求，显示「无项目查看权限」。
- 加载失败：栏内错误态 + 「重试」，不弹全局 `message`。

### 6.5 `components/ZoneAssetPane.tsx`

```tsx
export function ZoneAssetPane({
  projectId,
  zoneId,            // null = 「全部分区」
  onZoneChange,      // (zoneId: number | null) => void
  zones,             // useProjectZones 的结果（Tab 数据源与增删改都由它驱动）
  zonesLoading,
  zonesFailed,
  reloadZones,
  ...                // canViewProject / canUpdateProject / canLedger*
}: { ... })
```

- `Tabs` 的 `items` = `[{ key: 'all', label: '全部分区' }, ...zones.map(...)]`；`onChange` 把 `'all'` 映射回 `null` 后回调 `onZoneChange`。
- Tab label 用自定义节点渲染：`名称` + `Badge/span` 显示 `assetCount`。
- `tabBarExtraContent`：`新增分区`（primary）/ `编辑` / `删除`；非 `canUpdateProject` 时整块不渲染；选中「全部分区」时后两者 `disabled`。
- 资产表格自持 `page` / `pageSize=10` / `rows` / `loading` / `loadFailed`，在 `projectId` 或 `zoneId` 变化时重置分页并重拉（`useEffect` 依赖 `[projectId, zoneId, reloadKey]`）。
- 请求：`GET /assets?projectId=&zoneId=&page=&pageSize=`，`zoneId == null` 时不拼该参数。
- 「全部分区」下显示「分区」列（取 `zoneName`），其他情况隐藏。
- 写操作成功后由父级统一编排「重拉分区 + 重拉资产」（§5.2），本组件不自行决定刷新范围。

### 6.6 `pages/ProjectZonesPage.tsx`（编排层）

只持有跨栏共享状态并落实 §5.2。**`projectId` / `zoneId` 的唯一真相是 URL query**（§5.4）：

```tsx
const [searchParams, setSearchParams] = useSearchParams();
const projectId = toPositiveInt(searchParams.get('projectId'));   // null = 未选项目
const zoneId = toPositiveInt(searchParams.get('zoneId'));         // null = 全部分区
const [reloadKey, setReloadKey] = useState(0);                    // 「刷新」按钮与写操作后重拉
const zones = useProjectZones(projectId);

/** 写 query 用 replace：Tab 切换不该在浏览器历史里留下每一步 */
const selectProject = (id: number) =>
  setSearchParams({ projectId: String(id) }, { replace: true });   // 第 1 条：切项目 → Tab 重置
const selectZone = (id: number | null) =>
  setSearchParams(
    id == null ? { projectId: String(projectId) }
               : { projectId: String(projectId), zoneId: String(id) },
    { replace: true },
  );
```

- 切项目时**不传 `zoneId`** → `zoneId` 自动为 `null`（即「全部分区」），恰好就是 §5.2 第 1 条要求的「Tab 重置为全部分区」，无需额外的 `useEffect` 去清空状态。
- 资产区由 `zoneId` / `projectId` / `reloadKey` 的变化驱动重拉（§6.5）。
- 分区写操作（`ZoneFormModal` 的 `onSubmit`、删除确认的 `onOk`）成功 → `await zones.reload()` + `setReloadKey(k => k + 1)`（第 3、4 条）。`zones.reload()` 是必要的：它刷新 Tab 栏本身，而 `reloadKey` 只作用于资产区。
- 「删除当前分区」成功后额外 `selectZone(null)`（第 4 条：回到「全部分区」）；若删的不是当前 Tab，则保持当前 `zoneId` 不变。
- 资产的写操作发生在独立表单页（§6.7），返回时本组件整页重新挂载 → 自动满足第 5 条，无需任何回调。

- 删除分区：`confirmDelete({ name: zone.name, resourceLabel: '分区' })`，后端 400 原样 `message.error`（「该分区下有 N 项资产，无法删除」/「已有后续记录，请先处理后再删除」）。
- 删除资产：`confirmDelete({ name: asset.name, resourceLabel: '资产' })`，后端 400 原样透出（「非空置资产不可删除」）。
- 权限：一律 `usePerm(code, action)` 显式判定（§4），不写 `perm` 字面量（§4.1）。

### 6.7 `AssetFormPage` 的 query 参数支持

| 参数 | 作用 |
|------|------|
| `projectId` | 新增时预填「项目」；编辑时忽略（以接口返回为准） |
| `zoneId` | 新增时预填「分区」；编辑时忽略。缺省表示「未指定」 |
| `lockScope=1` | 禁用「资产公司」「项目」「分区」三个下拉并显示提示「由项目分区管理进入，归属已锁定」 |

- 新增：并入 `Form` 的 `initialValues`（`{ assetType: 'property', projectId, zoneId }`）。
- 编辑：不预填归属（避免与后端返回值竞争），只按 `lockScope` 禁用下拉。
- **`lockScope=1` 时必须一并推导并锁定「资产公司」**（值由 `GET /projects/{projectId}` 取得，
  与 `projectId` / `zoneId` 一次 `setFieldsValue` 写入）。这**不是可选的美化，而是锁定能否成立的前提**：
  - 「项目」下拉的选项来自 `assetCompanyId`（`/projects?companyId=`）。不预填公司 ⇒ 下拉无匹配项，
    只会显示原始 id（如 `1`）而不是项目名，用户无从确认自己锁在哪个项目上；
  - 「资产公司」是**必填**项。靠用户手选会触发既有 `useCascadeReset`（资产公司变更 → 清空
    项目/分区），把刚锁住的两项清成空且因为 `disabled` 再也改不回来 —— 表单卡死，只能刷新重来；
  - 预填公司**不会**被这条级联清空：`useCascadeReset` 在 `previous === undefined` 时提前返回，
    首次写入不算「变更」（这一点已在实现中核对过）。
  - 查询契约因此**不变**（仍只有 `projectId` / `zoneId` / `lockScope`），多出的那次
    `GET /projects/{projectId}` 由资产表单页自己承担，调用方（§6.5）无需改动。
- 保存成功后：由硬编码的 `navigate('/assets')` 改为按 `location.state.from` 返回，fallback `/assets`。
  - 从资产台账进入时 `state.from = '/assets'` → 行为与现状**完全一致**；
  - 从本页进入时 `state.from = '/project-zones?projectId=..&zoneId=..'` → 精确回原页；
  - 无 `state.from` 的深链直达：沿用既有 `goBack()` 语义（浏览器上一页/兜底 `/assets`）。
- 「分区」下拉的选项来源已是 `GET /projects/{projectId}/zones`（既有逻辑），锁定后仅展示不可改。

---

## 7. 后端设计

**业务逻辑零改动**：无新接口、无新 `@RequiresPerm`、无 Service 改动、无实体改动。

新增：

| 文件 | 说明 |
|------|------|
| `src/main/resources/db/migration/V47__project_zone_menu.sql` | §3.1 的菜单行 + view 回填 |
| `src/test/java/com/ams/modules/asset/V47MigrationContractTest.java` | 迁移契约守卫（沿用 `V46MigrationContractTest` 风格） |

> 测试放在 `modules/asset/` 而非 `modules/system/`：被种子的菜单是 `asset.projectZone`、页面属资产域，与被守卫的特性就近（同 `AssetServiceZoneTest`）。菜单只是它的落地形式，测试的语义是「分区管理入口必须存在且可见」。

`V47MigrationContractTest` 断言：

1. 迁移文本包含 `asset.projectZone` 菜单行，且 `path` 为 `/project-zones`、`menu_type` 为 `menu`、父目录为 `asset`。
2. 迁移文本包含对 `asset.projectZone` 的 `view` 回填，且回填语句显式排除 `super_admin`、只出现 `'view'`（不出现 create/update/delete —— 写动作回填是 V45 §5.3 明令禁止的）。
3. 迁移**不触碰** `project_zone` / `asset` / `project` 等业务表（本项目其它迁移的同类硬约束，防止合并冲突误改）。

### 7.1 既有接口的复用点（无需改动，仅确认）

| 接口 | 复用点 |
|------|--------|
| `GET /projects` | 左栏项目列表：`keyword` / 分页 / 数据范围 / 资产聚合字段全部现成 |
| `GET /projects/{id}/zones` | Tab 数据源：`assetCount` / `assetArea` 现成，直接作 Tab 徽标与标题行 |
| `GET /assets` | `projectId` + `zoneId` **均可选**，天然支持「全部分区」Tab；`zoneName` 已回显 |
| `/assets` 写接口 | 归属锁定由前端 `lockScope` 负责；后端只兜底「分区必须属于所选项目」（`validateZone`），**不校验「资产公司 ↔ 项目」是否同一公司** —— 见 §8 的已知缺口 |

---

## 8. 错误处理与边界

| 场景 | 处理 |
|------|------|
| 项目列表加载失败 | 左栏错误态 + 「重试」，不弹全局 `message` |
| 未选项目 | Tab 栏与资产区引导空态，不发请求 |
| 分区列表加载失败 | Tab 栏错误态 + 「重试」；资产区可继续用「全部分区」（不依赖分区数据） |
| 资产列表加载失败 | 资产区错误态 + 「重试」，不影响 Tab 栏 |
| 删除挂资产/有后续记录的分区 | 后端 400，前端原样提示，分区保留、资产 `zone_id` 不被改动 |
| 删除非空置资产 | 后端 400「非空置资产不可删除」，前端原样提示 |
| 选中「全部分区」时点「编辑/删除」 | 按钮 `disabled`（分区不存在），不发请求 |
| `zoneId` 不属于所选项目 | 后端 400「分区不存在」（由既有 `requireProjectZone` 保证，不依赖前端传参正确性）；前端收到后回到「全部分区」并提示 |
| `?projectId=` 指向不可见项目 | 新增资产页的「项目」下拉预填后仍受数据范围约束（选项按 `assetCompanyId` 过滤）。**注意**：与下一行同一个缺口 —— 服务端不校验公司↔项目一致性，故不能假设「保存时后端会拒」。靠 §6.7 从入口侧锁定归属来规避 |
| `?lockScope=1` 被人为去掉 | 仅影响前端的禁用体验；后端仍保证「分区属于所选项目」（`validateZone` / `validateReferences`） |
| **已知缺口：公司 ↔ 项目一致性后端未校验** | `validateZone(projectId, zoneId)`（`AssetService:1044-1053`）只校验「分区 ∃ 且属于该项目」，**从不校验 `assetCompanyId` 是否拥有 `projectId`**。因此「资产公司 = A、项目 = B 公司的项目」这种不一致归属后端会照单接受。本期靠 §6.7 的「预填并锁定资产公司」从入口侧消除；**未在服务端兜底**，若将来出现其它可改归属的入口，需另开任务补校验（本期不做，见 §10） |
| 并发编辑 | 沿用既有口径（资产走 `@Version` 乐观锁并显式报冲突；分区本期不做乐观锁，最后一次保存为准） |
| 从资产表单页返回 | 整页重新挂载 → 分区 Tab 与资产列表天然重拉；选中项目与分区由 URL query 保留（§5.4） |

---

## 9. 验收标准

1. 侧栏「资产台账」目录下出现「项目分区管理」，点击进入 `/project-zones`；非超管角色在 V47 回填后即可见。
2. **左栏**为项目列表，可关键字搜索与分页；每行显示项目名称、资产宗数、资产面积；点击某项即选中并高亮。
3. 选中状态写入 URL（`?projectId=&zoneId=`）：刷新页面或复制链接重开后，选中的项目与分区保持不变；「全部分区」对应 `zoneId` 缺省。
4. **右侧上方**为分区 Tab 栏，第一个 Tab 是「全部分区」，其余为该项目的分区（Tab 上显示该分区资产数）；**右侧下方**为资产表格。
5. 左栏切换项目 → 分区 Tab 重新加载且**选中 Tab 重置为「全部分区」**、资产分页归 1 并重拉；未选项目时右侧为引导空态且未发出分区/资产请求。
6. 切换分区 Tab → 资产分页归 1 并重拉；「全部分区」Tab 显示项目全部资产并额外出现「分区」列，其他 Tab 隐藏该列。
7. 选中「全部分区」时「编辑」「删除」为禁用态。选中具体分区时「新增分区」「编辑」「删除」可用（有 `asset.project:update` 时）。
8. Tab 栏可新增分区（名称必填；排序留空则追加到末尾），保存后 Tab 栏即时出现新分区；可编辑名称/编码/排序/备注，保存后即时更新；资产面积/资产数不出现在表单中。
9. 删除无资产且无后续记录的分区成功且**选中 Tab 回到「全部分区」**；删除挂资产的分区被拒绝并提示资产数量，分区保留、资产 `zone_id` 未改动。
10. 资产表格按 `projectId + zoneId` 过滤并分页；标题行的「共 N 宗」与分页总数一致（不受分页影响）；选中具体分区时显示该分区面积合计。
11. 「新增资产」进入 `/assets/create` 时项目与分区已预填且**不可修改**（「全部分区」Tab 下只预填项目），保存后返回本页且原分区仍选中、资产列表出现新资产。
12. 资产写操作后 **Tab 上的资产数与面积同步刷新**（不出现「Tab 显示 12 宗、表里 13 行」）。
13. 点「刷新」→ 分区 Tab 与资产列表同时重拉，资产分页归 1。
14. 「编辑」进入 `/assets/{id}/edit?lockScope=1`，归属两项禁用；保存后返回本页。删除对空置资产成功、对非空置资产被拒且提示原样透出。
15. 缺各项权限的账号逐条符合 §4.2：按钮隐藏、无权限提示、且**未发出**对应请求（可用浏览器网络面板核对）。
16. 项目列表展开行里的分区维护（`ProjectZonesPanel`）行为与本页改动前**完全一致**（回归）。
17. 资产台账的「新增/编辑」入口行为与改动前**完全一致**（回归）。
18. `scripts/check-perm-invariants.mjs` 通过（STANDALONE_ROUTES 覆盖镜像，前端 `perm` 声明集合未扩大）。
19. `pnpm --filter admin-web build`（含 `tsc -b`）与 `lint` 通过；`cd backend && mvn test` 通过（含 `V47MigrationContractTest`）。
20. `AssetService` / `AssetController` / 既有三个分区接口与 `/assets` 接口**零改动**（可 diff 验证）。

---

## 10. 本期不做（Out of Scope）

- 「未划分区」独立 Tab（已被「全部分区」覆盖，不为它给 `GET /assets` 加 `zoneId IS NULL` 参数）。
- 分区内资产的批量新增/批量移动/批量删除。
- 分区 Tab 拖动排序（排序仍靠数值字段）。
- 分区名称/编码的项目内唯一约束。
- 左栏项目的增删改（项目 CRUD 仍在「项目管理」页）。
- 精简版资产弹窗表单（坚持复用 `AssetFormPage`）。
- 新增 `/project-zones/*` 后端接口组、新增 `asset.projectZone:create/update/delete` 权限码。
- 分区详情页 `ZoneDetailPage` 与分区后续记录（属 record-forms 设计，另行实现）。
- 卡片模式下的分区展开（属 project-list-zone-expand 设计）。
- **服务端「资产公司 ↔ 项目」一致性校验**：`validateZone` 只校验分区属于项目，不校验公司拥有该项目。
  本期只在入口侧（§6.7 锁定资产公司）规避，不在 `AssetService` 加校验 —— 加它会破坏本期
  「后端零业务改动」的约束，且需要一套跨表校验与配套用例，属独立专项。

---

## 11. 验证方式

- **后端**：`V47MigrationContractTest`（§7 三条断言）；`cd backend && mvn test` 全量回归，确认 `@RequiresPerm` 扫描与启动校验不受影响。
- **前端**：无测试框架，验证 = `pnpm --filter admin-web build`（含 `tsc -b`）+ `lint` + `node scripts/check-perm-invariants.mjs` + 手动路径（切项目 / 切 Tab / 分区增删改 / 分区内资产增删改 / 删除保护 / 缺权账号逐条对照 §4.2 / §5.2 七条联动逐条核对，含第 5 条的 Tab 计数刷新）。
- **回归**：项目列表展开行的分区维护、资产台账的新增/编辑入口行为不变。
