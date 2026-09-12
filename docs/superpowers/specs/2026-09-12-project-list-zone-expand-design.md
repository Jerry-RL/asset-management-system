# 项目管理列表：分区展开与增删改 设计

**日期**：2026-09-12
**状态**：已确认，待实现
**范围应用**：`backend`（`com.ams.modules.asset`）+ `frontend/admin-web`
**关联需求**：`docs/需求规格说明书.md` FR-AST-001；`docs/superpowers/specs/2026-09-12-menu-and-role-permission-design.md`（权限口径）

---

## 1. 背景与问题

当前项目分区的维护入口只有项目新增/编辑的两步走向导（`ProjectFormPage`，路由 `/projects/create`、`/projects/:id/edit`），第二步「项目分区配置」用一张可内联编辑的表格承载分区。项目列表页（`/projects`）由通用 `ResourcePage` 渲染，**没有任何展开能力**，因此在列表上看不到某个项目下有哪些分区，也无法就地维护。

具体缺口：

| 维度 | 现状 | 问题 |
|------|------|------|
| 列表页 | `ResourcePage` 只有平铺列 + 行点击进详情 | 看不到项目下的分区 |
| 分区维护 | 只能进两步走向导第二步整体保存 | 改一个分区名称也要走完整项目编辑并提交全部基本信息 |
| 后端分区接口 | 只有 `GET /projects/{id}/zones`；增删改全靠 `PUT /projects/{id}` → `replaceZones` 全量替换 | 没有分区级接口，无法就地增删改单个分区 |
| 交互 | 无 | 需求：列表模式下可展开折叠查看所有分区，并对分区做新增/编辑/删除 |

**目标**：在项目列表（列表模式）中，每个项目行可展开查看其全部分区，并支持对分区做新增、编辑、删除；不改变卡片模式与既有两步走向导。

---

## 2. 已确认决策

| 项 | 决策 | 理由 |
|----|------|------|
| 展开行内的分区交互 | **展开行内展示分区列表；新增/编辑用弹窗表单，删除就地二次确认** | 与现有 `ResourcePage` 的弹窗风格一致，交互成本最低（用户确认） |
| 删除挂资产的分区 | **禁止删除**，提示「该分区下有 N 项资产，无法删除」 | 避免静默把资产的 `zone_id` 置空造成数据静默变更（用户确认） |
| 权限归类 | **三个操作统一复用 `asset.project:update`** | 分区是项目配置的一部分；不新增菜单种子与权限矩阵行；`asset.project:update` 已存在于 `AssetController`，`PermissionRegistry` 启动校验与 `check:perm` 不受影响（用户确认） |
| 实现方案 | **A：`ResourcePage` 增加通用可选 `expandable` + 独立 `ProjectZonesPanel` + 3 个分区级接口** | 改动面最小、其它资源零影响、分区逻辑隔离在单一组件；方案 B（配置化嵌套子资源）YAGNI，方案 C（专属列表页）需重写分页/筛选/统计/权限且触及路由镜像硬校验 |
| 生效范围 | 仅列表模式 | 需求明确「列表模式下」；卡片模式不可展开，保持不变 |

---

## 3. 交互设计

### 3.1 列表模式

- `/projects` 表格每行前置展开箭头（antd `expandable`）。
- 展开后在项目行下方渲染「该项目分区」面板。
- 卡片模式（`config.card` 的卡片视图）不出现展开入口。

### 3.2 分区面板

| 列 | 来源 | 说明 |
|----|------|------|
| 分区名称 | `name` | |
| 分区编码 | `code` | |
| 资产面积(㎡) | `assetArea` | **只读**，该分区下资产面积合计 |
| 资产数 | `assetCount` | **只读**，该分区下资产数量 |
| 排序 | `sort` | |
| 备注 | `remark` | |
| 操作 | — | 「编辑」「删除」 |

- 面板标题行右侧「新增分区」按钮。
- 空态：「暂无分区」+ 新增入口。
- 新增/编辑弹窗字段：分区名称（必填）、分区编码、排序、备注；资产面积/资产数不出现在表单中。
- 删除：二次确认；若有资产，后端拒绝，前端 `message.error` 原样透出「该分区下有 N 项资产，无法删除」。

### 3.3 数据加载

- 面板**懒加载**：只有项目行被展开时才请求 `GET /projects/{id}/zones`，避免列表页一次性发出 N 个请求。
- 每次展开都重新拉取（包括折叠后再次展开），保证显示最新数据，不做缓存失效推断。
- 新增/编辑/删除成功后**只重拉本面板的分区列表**，不重载外层项目列表（项目列表当前没有分区计数字段，无需刷新）。

---

## 4. 后端设计

### 4.1 接口清单

新增到 `AssetController`，全部复用 `assertProject(id)`（数据范围对象级断言）：

| 方法 | 路径 | 权限 | 审计动作 | 说明 |
|------|------|------|----------|------|
| POST | `/api/v1/projects/{id}/zones` | `asset.project:update` | `create_project_zone` | 新增分区 |
| PUT | `/api/v1/projects/{id}/zones/{zoneId}` | `asset.project:update` | `update_project_zone` | 编辑分区 |
| DELETE | `/api/v1/projects/{id}/zones/{zoneId}` | `asset.project:update` | `delete_project_zone` | 删除分区（挂资产时拒绝） |

请求体沿用实体 `ProjectZone`（与 `createAsset(@RequestBody Asset)` 风格一致），仅取 `name` / `code` / `sort` / `remark`，**忽略请求体里的 `id` 与 `projectId`**，归属一律由路径参数决定。

### 4.2 业务逻辑（伪代码）

```
createProjectZone(projectId, zone):
    project = getProject(projectId)                     # 不存在 → 404
    name = trim(zone.name)
    if name is blank: → 400 "请填写分区名称"
    if zone.sort == null:
        zone.sort = max(sort of project zones) + 1      # 无分区时从 0 开始
    zone.id = null
    zone.projectId = projectId
    zone.name = name
    insert(zone)
    return zone with assetArea = 0, assetCount = 0      # 与列表接口字段形态一致

updateProjectZone(projectId, zoneId, zone):
    getProject(projectId)
    existing = selectById(zoneId)
    if existing == null or existing.projectId != projectId: → 400 "分区不存在"
    name = trim(zone.name)
    if name is blank: → 400 "请填写分区名称"
    existing.name = name
    existing.code = zone.code
    existing.sort = zone.sort == null ? existing.sort : zone.sort
    existing.remark = zone.remark
    updateById(existing)
    fillZoneAssetStats([existing])
    return existing

deleteProjectZone(projectId, zoneId):
    getProject(projectId)
    zone = selectById(zoneId)
    if zone == null or zone.projectId != projectId: → 400 "分区不存在"
    assetCount = count(asset where zoneId = zoneId)     # Asset 为物理删除，无需过滤软删
    if assetCount > 0: → 400 "该分区下有 {assetCount} 项资产，无法删除"
    deleteById(zoneId)
```

- `sort` 冲突不做唯一约束（表上也没有），与现有 `replaceZones` 口径一致，仅按 `sort` 升序展示。
- 分区名称/编码不做唯一校验，与现有行为一致。
- **不改** `replaceZones` / `PUT /projects/{id}`：两步走向导仍走原路径整体保存，分区 id 稳定语义不变。两个入口操作同一张表，字段口径一致。

### 4.3 权限与注册表影响

- `asset.project:update` 已由 `AssetController.updateProject` 声明，`PermissionRegistry` 启动时校验的「menuCode 必须存在于 `menu` 表」与「action 在词表内」均已满足。
- `scripts/check-perm-invariants.mjs` 的后端集合由 `@RequiresPerm` 扫描得出，新增注解只是重复同一码，不改变集合；前端也不新增 `perm` 声明以外的码。

---

## 5. 前端设计

### 5.1 `ResourcePage` 增加通用「可展开行」

`ResourcePage.tsx` 新增可选配置，**仅在列表模式**透传给 antd `Table`：

```ts
export interface ExpandableConfig {
  /** 展开行渲染；row 为当前列表行 */
  render: (row: Row) => React.ReactNode;
  /** 可选：某行是否可展开（不配则均可展开） */
  rowExpandable?: (row: Row) => boolean;
}

export interface ResourceConfig {
  // ...既有字段
  expandable?: ExpandableConfig;
}
```

- 列表分支：`<Table expandable={config.expandable ? { expandedRowRender: (row) => config.expandable!.render(row), rowExpandable: config.expandable!.rowExpandable } : undefined} ... />`。
- 卡片分支不变。
- 展开行由 antd 渲染为独立 `<tr>`，不会命中 `onRow.onClick`（外层行点击进详情）—— 实现后需手动确认面板内点击不会触发跳转；若有冒泡则用 `stopPropagation` 处理。

### 5.2 新增 `components/ProjectZonesPanel.tsx`

- 入参：`projectId: number`。
- 挂载时 `GET /projects/{projectId}/zones`，加载中显示 `Spin`，失败显示错误态 + 「重试」。
- 渲染分区块表格 + 「新增分区」按钮 + 新增/编辑 `Modal(Form)` + 删除 `confirmDelete`。
- 权限：用 `<PermissionGuard perm="asset.project:update">` 包裹「新增分区」按钮与行内「编辑/删除」，无权时隐藏。
- 新增提交 `POST /projects/{projectId}/zones`；编辑提交 `PUT /projects/{projectId}/zones/{zoneId}`；删除 `DELETE /projects/{projectId}/zones/{zoneId}`。
- 三个操作成功后重拉本面板列表。

### 5.3 `modules.tsx` 的 `projects` 配置

- 在配置对象**外部**定义具名函数，避免在 `RESOURCES` 对象字面量里嵌 JSX 影响 `check:perm` 的正则解析：

```ts
const renderProjectZones = (row: Record<string, unknown>) => (
  <ProjectZonesPanel projectId={Number(row.id)} />
);

// RESOURCES.projects
expandable: { render: renderProjectZones },
```

- `expandable` 只加在 `projects` 上，其它资源页不受影响。

---

## 6. 错误处理与边界

| 场景 | 处理 |
|------|------|
| 分区面板加载失败 | 面板内错误提示 + 「重试」，不影响外层项目列表 |
| 新增/编辑校验失败 | 弹窗内表单校验；后端 400 时 `message.error` 透出原因 |
| 删除挂资产的分区 | 后端返回 400，前端原样提示「该分区下有 N 项资产，无法删除」 |
| `zoneId` 不属于该项目 | 后端 400「分区不存在」（不依赖前端传参正确性） |
| 并发编辑 | 本期不做乐观锁；同一分区先后保存以最后一次为准（与现有项目保存口径一致） |
| 展开行点击冒泡 | 实现后手动确认；若触发外层跳转则在面板根节点 `stopPropagation` |

---

## 7. 验收标准

1. `/projects` 列表模式下每个项目行可展开/折叠；展开后显示该项目的全部分区（名称/编码/资产面积/资产数/排序/备注）。
2. 卡片模式不出现展开入口；二步走向导（`/projects/create`、`/projects/:id/edit`）行为不变。
3. 展开面板可新增分区（名称必填），保存后列表出现新分区且排序缺省生效。
4. 展开面板可编辑分区名称/编码/排序/备注，保存后列表即时更新。
5. 删除无资产分区成功；删除挂资产分区被拒绝，提示含资产数量，且该分区仍存在、资产 `zone_id` 未被改动。
6. 无权（无 `asset.project:update`）账号看不到「新增分区」「编辑」「删除」入口，直接调用三个接口返回 403。
7. `zoneId` 不属于路径中项目时返回 400。
8. `scripts/check-perm-invariants.mjs` 通过；`tsc -b` 与 `eslint` 通过。

---

## 8. 本期不做（Out of Scope）

- 卡片模式下的分区展开。
- 分区在项目列表中的「分区数」列 / 排序拖拽。
- 分区名称或编码的项目内唯一约束。
- 批量导入/导出分区、分区的数据范围独立授权（统一随 `asset.project:update`）。
- 将通用 `ResourcePage` 做成配置驱动的嵌套子资源（方案 B）。

---

## 9. 验证方式

- **后端**：新增 `AssetService` 分区 CRUD 的 JUnit 用例（参照现有 service 测试的 Mockito 风格），覆盖：新增缺省排序、编辑时 `zoneId` 不属于项目被拒、删除挂资产分区被拒、删除空分区成功。
- **前端**：无测试框架，验证 = `pnpm --filter admin-web build`（含 `tsc -b`）+ `lint` + `node scripts/check-perm-invariants.mjs` + 手动路径（展开/折叠、增删改、无权账号、删除保护）。
- **回归**：项目新增/编辑两步走向导保存分区仍正常（`PUT /projects/{id}` 未改动）。
