# 菜单管理 + 角色权限管理 设计

**日期**：2026-09-12
**状态**：已确认，待实现
**范围应用**：`backend`（`com.ams.modules.system`、`com.ams.platform.security`、`com.ams.modules.org`）+ `frontend/admin-web`
**关联需求**：`docs/需求规格说明书.md` FR-SYS-001、NFR-SEC-002；ADR `0003-jwt-auth-and-rbac`

---

## 1. 背景与问题

当前「菜单管理不清晰」与「角色权限只到角色 CRUD」的根因，经代码核对如下：

| 维度 | 现状 | 问题 |
|------|------|------|
| 菜单数据 | `menu(parent_id, name, code, path, icon, sort, menu_type, status)`，**无任何种子数据** | 权限点无来源 |
| 菜单接口 | `GET /system/menus`（按权限过滤的树）、`GET /system/menus/all`（扁平）、增删改 | 树接口未被管理页使用 |
| 菜单前端 | 侧边栏 100% 来自静态常量 `frontend/admin-web/src/pages/modules.tsx` 的 `MENU`（18 目录 / 40+ 菜单）；`/system/menus` 页面是扁平 `ResourcePage` 列表 | **DB 菜单与实际导航完全脱节**：改了不影响侧边栏；列表无树、无上级、无状态列 |
| 角色 | `role(code, name, data_scope, status)` | 前端只有角色增删改，**没有权限勾选矩阵**（接口已存在但未接 UI） |
| 权限 | `role_permission(role_id, menu_code, action, data_scope)`；`RbacService` 按 `menu_code:action` 判定 | `role_permission.data_scope` 定义后**从未使用**；`action` 无固定词表 |
| 拦截 | `RbacService.assertPermission(...)` 已定义 | **全项目无任何调用**；前端从未消费 `user.permissions` → 操作级权限未闭环 |
| 数据隔离 | `companyScope = 生效公司 + 全部下级子树` | A/B 无关公司天然互斥 ✅、上级默认可见下级 ✅；但**无「显式排除」配置**；且「空集合 = 不限公司」的语义在做减法后会产生越权风险 |

**目标**：
1. 让菜单管理成为清晰的「模块（目录）→ 页面（菜单）」树，并成为侧边栏的唯一来源。
2. 角色权限覆盖**页面级 / 模块级 / 操作级**三层，并落地可配置的数据隔离。
3. 建立操作级拦截闭环（框架先行，核心模块接入）。

---

## 2. 已确认决策

| 项 | 决策 | 理由 |
|----|------|------|
| 菜单数据来源 | **混合（A）**：DB `menu` 表为唯一来源，前端保留「路由 → 组件」注册表，侧边栏渲染/排序/命名/显隐读 DB | 消除脱节，同时避免全动态路由的高风险改造 |
| 数据隔离排除配置 | **角色级排除清单（A）**：默认 = 所属公司 + 全部下级子树；角色可勾选「排除公司」，命中即整棵子树不可见 | 与 SRS「角色绑数据权限」一致，简单可解释 |
| 操作级建模 | **「页面 × 固定动作」矩阵（A）**：`menu` 只存目录/菜单；动作来自后端固定词表；权限存 `(menu_id, action)` | 矩阵 UI 清晰、无冗余，兼容既有 `menuCode:action` 口径 |
| 拦截落地范围 | **框架先行 + 核心模块接入（A）** | 满足「默认拒绝」方向，控制回归面 |
| 生效时机 | 即时生效 | 权限每次请求由 `JwtAuthenticationFilter → RbacService.buildLoginUser` 实时组装，无缓存失效问题 |

---

## 3. 菜单管理设计

### 3.1 模型：两级菜单 + 页面级权限点

| 概念 | `menu_type` | 说明 |
|------|-------------|------|
| 模块（目录） | `dir` | 侧边栏分组。必须有 `code`，**禁止 `path`** |
| 页面（菜单） | `menu` | 实际路由。必须有 `code` + `path`，且必须挂在某个 `dir` 下 |
| 操作 | — | **不是菜单节点**，来自固定动作词表（见 4.2） |

- 层级**限制为两级**（目录 → 菜单），避免无限层级导致的展示与管理混乱。
- `code` 是权限命名空间，**发布后不可变**（`role_permission` 依赖它）；`path` 为前端路由，可由静态注册表兜底渲染。
- `status=0` 表示停用：侧边栏隐藏，但权限点保留，避免误删导致角色配置失效。

### 3.2 表调整

```sql
-- 唯一约束：code 是权限命名空间，必须全局唯一
ALTER TABLE menu ADD CONSTRAINT uk_menu_code UNIQUE (code);

-- 新增种子：V45__seed_menu_tree.sql
-- 按 modules.tsx 的 MENU 逐组落库：目录 + 菜单
-- 列序：(code, name, menu_type, path, sort, parent_id)
-- 例：('asset',        '资产台账', 'dir',  NULL,     10, NULL)
--     ('asset.ledger', '资产台账', 'menu', '/assets', 10, <asset.id>)
```

### 3.3 菜单管理页重做

替换当前 `/system/menus` 的 `ResourcePage` 配置，改为专用页面：

- 树形表格，列：名称 / 编码 / 路由 / 图标 / 类型 / 排序 / 状态 / 操作。
- 新建/编辑抽屉：类型、上级目录（菜单必选）、名称、编码、路由、图标、排序、状态。
- 校验规则：
  - `code` 全局唯一，创建后不可修改（修改需走专门的重命名流程，当前不做）。
  - 目录禁止填 `path`；菜单必须选择上级目录。
- 删除保护：存在子节点或已被 `role_permission` 引用时，**只能停用，不能删除**。

### 3.4 前端侧边栏改造

- `AdminLayout` 改为读取 `GET /system/menus`（后端已返回「按权限过滤后的树」）。
- `modules.tsx` 的 `MENU` 降级为**路由注册表**：提供「path → 组件 / 标题兜底 / 图标」。
- 职责划分：DB 决定「显示哪些、顺序、命名」；注册表决定「怎么渲染」。
- 兜底策略：
  - DB 中存在但注册表缺失的节点 → 忽略并记录日志（避免脏数据白屏）。
  - 注册表存在但 DB 停用/缺失的节点 → 不显示。
- `PageTabs`、`menuIcons`、`helpManual` 等依赖 `MENU` 的位置，统一改为消费「API 菜单树 + 注册表」的合并结果。

---

## 4. 角色权限管理设计

### 4.1 三层授权定义

| 层级 | 载体 | 判定码 |
|------|------|--------|
| 模块级 | 目录勾选 = 批量授予其下全部页面 | 目录 `code`（用于全选/半选 UI） |
| 页面级 | 菜单 `code` + `view` | `asset.ledger:view` |
| 操作级 | 菜单 `code` + 动作 | `asset.ledger:update` |

`RbacService.menusFor` 的可见性口径保持：菜单可见 ⇔ 角色拥有该 `code:view`（目录可见 ⇔ 任一子菜单可见）。

### 4.2 固定动作词表

后端枚举 `PermissionAction`（集中定义，前端由接口下发，不硬编码两份）：

```
view, create, update, delete, export, import, approve, audit, assign
```

> `role_permission` 无任何种子数据，统一为新词表可无损完成；V2 注释中的 `add/edit` 一并废弃。

### 4.3 表调整

```sql
-- role_permission：绑定 menu_id、加唯一约束、移除从未使用的 data_scope
-- menu_code 保留为冗余列（用于日志可读性与兼容兜底），以 menu_id 为权威判定依据
ALTER TABLE role_permission ADD COLUMN menu_id BIGINT;
ALTER TABLE role_permission ADD CONSTRAINT uk_role_menu_action UNIQUE (role_id, menu_id, action);
ALTER TABLE role_permission DROP COLUMN data_scope;

-- 新增角色数据排除表（角色级排除清单）
CREATE TABLE role_data_exclude (
    id         BIGSERIAL PRIMARY KEY,
    role_id    BIGINT      NOT NULL,
    company_id BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_role_company UNIQUE (role_id, company_id)
);
CREATE INDEX idx_role_data_exclude_role ON role_data_exclude (role_id);
```

### 4.4 角色权限页重做

- 左：角色列表（增删改、启用停用）。
- 右三个 Tab：
  - **功能权限**：菜单树 × 动作勾选矩阵；目录行支持全选 / 半选。
  - **数据权限**：公司树 + 排除清单（勾选即排除，命中子树整体置灰并给出提示）。
  - **成员**：该角色下的用户列表。
- 保存即时生效。

### 4.5 接口清单

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/system/menus/all` | 菜单完整树（管理页用，需超级管理员权限） |
| POST/PUT/DELETE | `/api/v1/system/menus[/{id}]` | 菜单增删改（沿用，补充校验） |
| GET | `/api/v1/system/permission-actions` | 动作词表（供矩阵渲染） |
| GET | `/api/v1/system/roles/{roleId}/permissions` | 返回「菜单树 + 已授动作 + 动作词表」 |
| PUT | `/api/v1/system/roles/{roleId}/permissions` | 批量替换（`[{menuId, actions:[...]}]`） |
| GET | `/api/v1/system/roles/{roleId}/data-scope` | 默认范围说明 + 排除清单 + 候选公司树 |
| PUT | `/api/v1/system/roles/{roleId}/data-scope` | 保存排除清单（`companyIds`） |

---

## 5. 数据隔离设计

### 5.1 默认口径（保持）

`可访问 = 所属公司 + 全部下级子树`

- A 公司与 B 公司无隶属关系 → 天然互斥，A 员工看不到 B 数据 ✅
- A 是 B 的（多层）上级 → 默认可见 B 数据 ✅

### 5.2 新增：角色级排除清单

**算法（替代 `RbacService.companyScope`）**

```
scope(user):
    if user.isSuperAdmin():
        return UNRESTRICTED                      # 系统兜底账号，永不受限

    baseline = (dataScope == 'all')
                   ? ALL_COMPANIES
                   : {homeCompanyId} ∪ descendantIds(homeCompanyId)

    excluded = ∅
    for each role of user's enabled roles:
        for each companyId of role_data_exclude[role]:
            excluded ∪= {companyId} ∪ descendantIds(companyId)   # 命中即整棵子树

    allowed = baseline − excluded
    return allowed.isEmpty() ? {-1} : allowed     # 哨兵；绝不能"空 = 全量"
```

**关键修正**：现有实现里**空集合 = 不限公司**。做减法后空集合必须显式降级为哨兵 `{-1}`，否则会从「排除干净」意外变成「放开全量」。
**语义说明**：排除清单对 `all` 基线同样生效（正是「管理员限制访问范围」的用途）；仅 `super_admin` 例外。
**多角色合并**：默认范围仍取最宽（`widen`），排除清单取并集 —— 即任一角色排除的公司都生效。

### 5.3 落点改造

- 仅改写 `RbacService.companyScope()`，`assertCompanyAccess()` 与 `applyCompanyScope()` 自动继承新口径，各业务列表无需逐处修改。
- `LoginUser` 增加 `excludedCompanyIds`（可选，便于前端提示与调试）。

---

## 6. 操作级拦截闭环设计

### 6.1 后端

- 注解 `@RequiresPerm("asset.ledger:update")` + `PermissionInterceptor`（或 AOP 切面）。
- 判定逻辑：
  1. 无登录主体 → 401；
  2. `super_admin` → 放行；
  3. `rbacService.assertPermission(user, menu, action)` 不通过 → 403（复用 `ErrorCode.FORBIDDEN`）。
- 过渡开关 `ams.security.strict-perm`（默认 `false`）：未接入注解的接口保持「仅登录校验」，全部核心模块接入后置为 `true`。
- 数据范围校验沿用 `assertCompanyAccess` / `applyCompanyScope`；带 `companyId` 的写操作必须显式校验。

### 6.2 前端

- `usePerm()` → `can(menuCode, action)`；`<PermissionGuard perm="asset.ledger:update">` 包裹按钮。
- `ResourcePage` / `TableActions` 支持按权限声明显隐行操作与工具栏按钮。
- 路由守卫 `<RequirePerm code="asset.ledger">`：无 `view` → 403 页面。
- 登录后 `/system/me` 已返回 `permissions`，直接复用。

### 6.3 首批接入清单

系统管理（角色 / 菜单 / 字典 / 用户）、组织架构（公司 / 部门 / 人员）、资产台账（项目 / 资产）的写操作。

其余模块登记 TODO 清单跟踪；未接入接口在过渡期为**已知缺口**（登录即可访问），不得视为已完成。

---

## 7. 迁移与实施顺序

1. **迁移** `V45`：`menu` 唯一约束 + 菜单种子；`role_permission` 改 `menu_id` + 唯一约束并删 `data_scope`；新增 `role_data_exclude`。
2. **后端**：`PermissionAction` 枚举、`RbacService.companyScope` 改造、菜单树 / 权限矩阵 / 数据范围三组接口与校验。
3. **前端**：菜单管理页 + 角色权限页（替换 `ResourcePage` 配置）、侧边栏改读 API（保留静态兜底）。
4. **拦截闭环**：注解 + 拦截器 + `usePerm` + 路由守卫，按清单逐模块接入。

---

## 8. 验收标准

1. 菜单管理页以树展示目录/菜单，可新增、编辑、排序、启停；删除被引用节点被拒绝并提示。
2. 侧边栏内容与顺序来自 DB 菜单表；停用某菜单后侧边栏即时消失；DB 脏数据不会导致白屏。
3. 角色权限页可对「菜单 × 动作」矩阵勾选并保存，重新登录后前端按钮与后端接口行为一致。
4. A、B 无隶属公司互不可见；A 为 B 上级时默认可见 B；在角色上排除 B 后，A 的员工不能再看到 B 的数据（含下级）。
5. 排除清单清空后行为回到默认子树；`super_admin` 不受排除影响。
6. 未授予 `asset.ledger:update` 的账号调用资产更新接口返回 403，前端不展示对应按钮。

---

## 9. 本期不做（Out of Scope）

- 菜单三级及以上层级、动态路由按需注册（前端路由仍为编译期注册表）。
- 按「模块 × 公司」的细粒度数据范围矩阵（`role_permission.data_scope` 直接移除）。
- 用户级数据范围覆盖（仅角色级排除清单）。
- 权限缓存层（当前每请求实时组装；未来如需可加短 TTL 缓存）。
- 全量模块的操作级拦截接入（本期仅框架 + 核心模块）。

---

## 10. 风险与取舍

| 风险 | 应对 |
|------|------|
| 侧边栏改读 DB 依赖种子完整性，漏种子会导致菜单缺失 | 前端保留路由注册表兜底；`V45` 种子对齐静态 `MENU` 并加测试断言数量一致 |
| 两级菜单上限可能不满足未来需求 | 本期明确上限；如需三级再改树组件与校验 |
| `role_permission` 从 `menu_code` 改为 `menu_id` 影响既有判定 | 无历史权限数据，迁移为纯结构变更；保留 `menu_code` 冗余用于日志与兜底 |
| 渐进拦截期间存在未接入接口 | 用 TODO 清单显式跟踪，`strict-perm` 开关控制切换时机 |
| 「空 = 全量」语义被破坏引发越权 | 引入哨兵 `{-1}` 显式表达「无可见公司」，并补单测覆盖排除到空的场景 |
