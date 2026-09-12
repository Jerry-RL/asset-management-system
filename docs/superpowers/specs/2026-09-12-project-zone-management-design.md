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

**目标**：新增顶级侧栏页「项目分区管理」（`/project-zones`），页面内「选项目 → 管分区 → 管该分区资产」，分区与资产的增删改全部复用既有接口与既有表单页。

---

## 2. 已确认决策

| 项 | 决策 | 理由 |
|----|------|------|
| 入口形态 | **顶级侧栏菜单**「项目分区管理」，路径 `/project-zones` | 用户确认；分区成为可直达的功能域 |
| 页面布局 | **顶部选项目 + 左栏分区列表（可增删改）+ 右栏该分区资产列表**，同页联动 | 用户确认；三层关系在一屏内可见，切换分区不产生页面跳转 |
| 分区内资产的增删改 | **复用 `AssetFormPage`**（`/assets/create`、`/assets/:id/edit`），用 query 参数预填并锁定项目+分区 | 用户确认；完整表单的字典级联、图片上传、乐观锁版本号全部免费复用，不另造精简弹窗 |
| 接口 | **零新增后端接口**，全部复用既有 | 见 §2.1 |
| 页面权限 | 页面级 `asset.projectZone:view`（镜像推导）+ 动作级复用既有已强制码 | 见 §4 |
| 新菜单可见性 | **V47 向所有非超管角色回填 `asset.projectZone:view`** | 用户确认；与 V45 §5.1「导航类菜单回填 view 以保持可见性」同口径，避免新功能上线即不可见 |
| 「未划分区」资产桶 | **本期不做** | 用户确认；`zoneId IS NULL` 的资产在资产台账统一查看，不为它给 `GET /assets` 加参数 |

### 2.1 为什么复用接口而不是新建 `/project-zones/*`

本仓已经为「同一语义两条写入路径」付过代价：`replaceZones`（项目 PUT 全量替换）与 `deleteProjectZone`（分区级 DELETE）曾对「分区能不能删」给出不同判定，最后靠抽出 `assertZoneRemovable` 这个唯一判定点才收敛（见 record-forms 设计 §7.3）。

新增 `POST/PUT/DELETE /project-zones/*` 会把这套校验（归属断言、有资产拒绝、有后续记录拒绝、软删过滤）复制第二份，必然再次漂移。因此本设计坚持：

- 分区增删改 → `POST /projects/{id}/zones`、`PUT/DELETE /projects/{id}/zones/{zoneId}`
- 分区资产读写 → `GET/POST /assets`、`PUT/DELETE /assets/{id}`

代价是页面守卫码与动作判定码不同源（见 §4），这个代价被显式接受并在 §7 逐条兜底。

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
| 项目下拉 | `asset.project:view` | 顶部工具栏显隐 | `GET /projects` |
| 分区列表 | `asset.project:view` | 左栏是否请求 | `GET /projects/{id}/zones` |
| 新增/编辑/删除分区 | `asset.project:update` | `usePerm('asset.project','update')` | `POST/PUT/DELETE .../zones` |
| 分区资产列表 | `asset.ledger:view` | 右栏是否请求 | `GET /assets?projectId&zoneId` |
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
| `asset.project:view` | 顶部项目下拉空态 + 「无项目查看权限」提示 |
| `asset.project:update` | 左栏「新增分区」与行内「编辑/删除」整块隐藏；分区列表只读可见 |
| `asset.ledger:view` | 右栏显示「无资产查看权限」，**不发出** `/assets` 请求 |
| `asset.ledger:create` | 右栏「新增资产」隐藏 |
| `asset.ledger:update` | 行内「编辑」隐藏 |
| `asset.ledger:delete` | 行内「删除」隐藏 |

---

## 5. 交互设计

### 5.1 布局

```
┌ 项目 [下拉选择 ▾]                                        [刷新] ┐
├──────────────┬──────────────────────────────────────────────────┤
│ 分区  [+新增] │ 分区名称 · 编码 · 排序 · 资产 N 宗 · 合计 X ㎡      │
│ ▸ A区   12宗  │                                  [+ 新增资产]      │
│ ▸ B区    8宗  │ ┌ 资产编号 名称 类型 楼层 面积 租控状态 操作 ┐    │
│ ▸ C区    0宗  │ │ ...                    编辑 / 一物一档 / 删除 │   │
│              │ └ 分页 ────────────────────────────────────┘    │
└──────────────┴──────────────────────────────────────────────────┘
```

- 顶部：项目下拉（可搜索，`GET /projects?page=1&pageSize=200`，受全局公司切换与数据范围约束）+「刷新」（重拉分区与资产）。
- 左栏（`lg:w-[280px]`）：分区列表，行内展示「名称 / 编码 / 资产数 / 资产面积合计 / 排序」，选中项高亮；行尾「编辑」「删除」；顶部「新增分区」。
- 右栏：所选分区的资产表格 + 分页。
- 响应式：小屏左栏折叠到右栏上方（`flex-col lg:flex-row`），与 `ProjectDetailPage` 一致。

### 5.2 状态与联动

| 触发 | 行为 |
|------|------|
| 初次进入，未选项目 | 左右两栏都是引导空态（「请先选择项目」），**不发**分区/资产请求 |
| 选择项目 | 拉 `GET /projects/{id}/zones`；清空选中分区、资产分页重置为 1 |
| 分区列表加载完成 | 若当前选中 `zoneId` 不在返回列表中，自动选中第一项；列表为空则置空 |
| 选中分区 | 拉 `GET /assets?projectId=&zoneId=&page=&pageSize=10` |
| 项目无分区 | 左栏空态「暂无分区」+「新增分区」入口 |
| 分区无资产 | 右栏空态「该分区暂无资产」+「新增资产」入口 |
| 分区切换到另一项目后再切回 | 每次重新拉取，不做缓存失效推断（与 `ProjectZonesPanel` 口径一致） |
| 分区增删改成功后 | 只重拉左栏分区列表；被删除的分区若正被选中则按 §5.2 的自动选中规则重选 |
| 资产增删改后返回本页 | 重新拉取当前分区的资产列表，选中分区不变 |

### 5.3 跳转与返回

- 「新增分区」「编辑分区」→ 弹窗表单（名称必填 / 编码 / 排序 / 备注），与 `ProjectZonesPanel` 共用同一个弹窗组件。
- 「新增资产」→ `/assets/create?projectId={id}&zoneId={id}&lockScope=1`，带 `state: { from: currentPath(location) }`。
- 「编辑资产」→ `/assets/{assetId}/edit?lockScope=1`，同样带 `state.from`。
- 「一物一档」→ `/assets/{assetId}/dossier`（新页签行为不变）。
- 返回本页时 query 串被保留（`currentPath` 已包含 query），因此能精确回到原项目 + 原分区。

---

## 6. 前端设计

### 6.1 新文件与改动清单

| 文件 | 变更 | 说明 |
|------|------|------|
| `src/pages/ProjectZonesPage.tsx` | 新建 | 「项目分区管理」页主体 |
| `src/lib/projectZones.ts` | 新建 | `ProjectZone` 类型 + `useProjectZones` 钩子（分区读写逻辑的单一来源） |
| `src/components/ZoneFormModal.tsx` | 新建 | 分区新增/编辑弹窗（两人口共用） |
| `src/components/ProjectZonesPanel.tsx` | 修改 | 改为消费共享类型与钩子；呈现形态（表格）不变 |
| `src/pages/ProjectFormPage.tsx` | 修改 | 删除本地 `ProjectZone` 副本，改用 `lib/projectZones.ts`（纯类型收敛） |
| `src/pages/AssetFormPage.tsx` | 修改 | 支持 `?projectId&zoneId&lockScope`；保存成功后按 `state.from` 返回 |
| `src/App.tsx` | 修改 | 新增路由（§3.2 #1） |
| `src/lib/routeRegistry.ts` | 修改 | `STANDALONE_ROUTES` + 注释里的菜单计数 64 → 65 |
| `src/lib/pathToCode.ts` | 修改 | 镜像新增 `/project-zones` |
| `src/pages/modules.tsx` | 修改 | `MENU` 新增菜单项 |
| `src/lib/menuIcons.tsx` | 修改 | `PATH_ICONS` 新增图标 |

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
- 加载失败**不弹全局 `message`**（与现状一致）：面板/分栏内显示错误态 + 重试即可。

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

### 6.4 `pages/ProjectZonesPage.tsx`

状态：`projectId` / `zoneId` / `assetPage` / `assetPageSize` / `reloadKey`（「刷新」按钮的自增计数器，驱动分区与资产同时重拉）/ `projects` / `assets`。

- 分区列表：`useProjectZones(projectId)`，仅在 `projectId != null && can('asset.project','view')` 时请求。
- 资产列表：`GET /assets?projectId=&zoneId=&page=&pageSize=`，仅在 `zoneId != null && can('asset.ledger','view')` 时请求。
- 本页只消费两个**确定返回 `PageResult`** 的接口（`GET /projects`、`GET /assets`），因此按 `PageResult<T>` 取值并以 `raw?.list ?? []` 兜底即可。**不引入 `ResourcePage` 那份 `normalizePage` 容错、也不重构 `ResourcePage`** —— 那份容错服务的是「部分资源接口返回裸数组」的历史形态，与本页无关，复制或迁移都是无谓的风险。
- 删除分区：`confirmDelete({ name, resourceLabel: '分区' })`，后端 400 原样 `message.error`（「该分区下有 N 项资产，无法删除」/「已有后续记录」）。
- 删除资产：`confirmDelete({ name: asset.name, resourceLabel: '资产' })`，后端 400 原样透出（「非空置资产不可删除」）。

### 6.5 `AssetFormPage` 的 query 参数支持

| 参数 | 作用 |
|------|------|
| `projectId` | 新增时预填「项目」；编辑时忽略（以接口返回为准） |
| `zoneId` | 新增时预填「分区」；编辑时忽略 |
| `lockScope=1` | 禁用「项目」「分区」两个下拉并显示提示「由项目分区管理进入，归属已锁定」 |

- 新增：并入 `Form` 的 `initialValues`（`{ assetType: 'property', projectId, zoneId }`）。
- 编辑：不预填归属（避免与后端返回值竞争），只按 `lockScope` 禁用下拉。
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

---

## 8. 错误处理与边界

| 场景 | 处理 |
|------|------|
| 项目列表加载失败 | 顶部 `message.error`；下拉空态 + 重试 |
| 未选项目 | 左右两栏引导空态，不发请求 |
| 分区列表加载失败 | 左栏错误态 + 「重试」，不影响右栏之外的其它区域 |
| 资产列表加载失败 | 右栏错误态 + 「重试」，不影响左栏 |
| 删除挂资产/有后续记录的分区 | 后端 400，前端原样提示，分区保留、资产 `zone_id` 不被改动 |
| 删除非空置资产 | 后端 400「非空置资产不可删除」，前端原样提示 |
| `zoneId` 不属于所选项目 | 后端 400「分区不存在」（由既有 `requireProjectZone` 保证，不依赖前端传参正确性） |
| `?projectId=` 指向不可见项目 | 新增资产页的「项目」下拉预填后仍受数据范围约束；保存时后端按既有级联校验拒绝 |
| `?lockScope=1` 被人为去掉 | 仅影响前端的禁用体验；归属越权由后端 `validateZone` / `validateReferences` 拦截 |
| 并发编辑 | 沿用既有口径（资产走 `@Version` 乐观锁并显式报冲突；分区本期不做乐观锁，最后一次保存为准） |
| 展开/左栏点击冒泡 | 本页无嵌套行点击，不涉及 |

---

## 9. 验收标准

1. 侧栏「资产台账」目录下出现「项目分区管理」，点击进入 `/project-zones`；非超管角色在 V47 回填后即可见（未回填前仅超管可见，属预期）。
2. 顶部可搜索选择项目；切换项目后左栏分区与右栏资产都按新项目刷新，资产分页重置为 1。
3. 左栏列出该项目全部分区（名称/编码/资产数/资产面积/排序），自动选中第一项；项目无分区时显示空态与「新增分区」入口。
4. 左栏可新增分区（名称必填；排序留空则追加到末尾），保存后列表即时出现新分区。
5. 左栏可编辑分区名称/编码/排序/备注，保存后即时更新；资产面积/资产数不出现在表单中。
6. 删除无资产且无后续记录的分区成功；删除挂资产的分区被拒绝并提示资产数量，分区保留、资产 `zone_id` 未改动。
7. 右栏按 `projectId + zoneId` 列出该分区全部资产并分页；「新增资产」进入 `/assets/create` 时项目与分区已预填且**不可修改**，保存后返回本页且原分区仍选中、资产列表出现新资产。
8. 右栏「编辑」进入 `/assets/{id}/edit?lockScope=1`，归属两项禁用；保存后返回本页。
9. 右栏「删除」对空置资产成功、对非空置资产被拒绝，提示原样透出。
10. 缺 `asset.project:update` 的账号看不到分区的新增/编辑/删除；缺 `asset.ledger:*` 的账号相应按钮隐藏；缺 `asset.ledger:view` 时右栏显示无权限提示且**未发出** `/assets` 请求。
11. 项目列表展开行里的分区维护（`ProjectZonesPanel`）行为与本页改动前**完全一致**（回归）。
12. 资产台账的「新增/编辑」入口行为与改动前**完全一致**（回归）。
13. `scripts/check-perm-invariants.mjs` 通过（RESOURCES/STANDALONE_ROUTES 覆盖镜像，前端 `perm` 声明集合未扩大）。
14. `pnpm --filter admin-web build`（含 `tsc -b`）与 `lint` 通过；`cd backend && mvn test` 通过（含 `V47MigrationContractTest`）。
15. `AssetService` / `AssetController` / 既有三个分区接口与 `/assets` 接口**零改动**（可 diff 验证）。

---

## 10. 本期不做（Out of Scope）

- 「未划分区」资产桶（`zoneId IS NULL`），以及为此给 `GET /assets` 增加参数。
- 分区内资产的批量新增/批量移动/批量删除。
- 分区拖动排序（排序仍靠数值字段）。
- 分区名称/编码的项目内唯一约束。
- 精简版资产弹窗表单（坚持复用 `AssetFormPage`）。
- 新增 `/project-zones/*` 后端接口组、新增 `asset.projectZone:create/update/delete` 权限码。
- 分区详情页 `ZoneDetailPage` 与分区后续记录（属 record-forms 设计，另行实现）。
- 卡片模式下的分区展开（属 project-list-zone-expand 设计）。

---

## 11. 验证方式

- **后端**：`V47MigrationContractTest`（§7 三条断言）；`cd backend && mvn test` 全量回归，确认 `@RequiresPerm` 扫描与启动校验不受影响。
- **前端**：无测试框架，验证 = `pnpm --filter admin-web build`（含 `tsc -b`）+ `lint` + `node scripts/check-perm-invariants.mjs` + 手动路径（切项目 / 分区增删改 / 分区内资产增删改 / 删除保护 / 缺权账号逐条对照 §4.2）。
- **回归**：项目列表展开行的分区维护、资产台账的新增/编辑入口行为不变。
