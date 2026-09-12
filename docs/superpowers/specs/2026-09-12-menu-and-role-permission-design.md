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
| 菜单前端 | 侧边栏 100% 来自静态常量 `frontend/admin-web/src/pages/modules.tsx` 的 `MENU`（24 目录 / 66 菜单）；`/system/menus` 页面是扁平 `ResourcePage` 列表 | **DB 菜单与实际导航完全脱节**：改了不影响侧边栏；列表无树、无上级、无状态列 |
| 角色 | `role(code, name, data_scope, status)` | 前端只有角色增删改，**没有权限勾选矩阵**（接口已存在但未接 UI） |
| 权限 | `role_permission(role_id, menu_code, action, data_scope)`；`RbacService` 按 `menu_code:action` 判定 | `role_permission.data_scope` 定义后**从未使用**；`action` 无固定词表 |
| 拦截 | `RbacService.assertPermission(...)` 已定义 | **全项目无任何调用**；前端从未消费 `user.permissions` → 操作级权限未闭环 |
| 数据隔离 | `companyScope = 生效公司 + 全部下级子树` | A/B 无关公司天然互斥 ✅、上级默认可见下级 ✅；但**无「显式排除」配置**；且「空集合 = 不限公司」的语义在做减法后会产生越权风险 |

**目标**：
1. 让菜单管理成为清晰的「模块（目录）→ 页面（菜单）」树：**DB 决定「显示哪些、顺序、命名、显隐」，编译期注册表决定「页面是否存在、如何渲染」**。新增页面仍需前端注册表变更并随版本发布（见 3.4）。
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
-- 列序：(code, name, menu_type, path, icon, sort, parent_id)
-- 例：('asset',        '资产台账', 'dir',  NULL,      'folder', 10, NULL)
--     ('asset.ledger', '资产台账', 'menu', '/assets', 'ledger', 10, <asset.id>)
--
-- 系统管理模块的 code **必须与 4.5 注解里写的字符串逐字一致**，否则 strict-perm
-- 一生效，系统管理接口对所有角色 403（矩阵里勾的 code 与断言用的 code 对不上）：
--     ('system',      '系统管理', 'dir',  NULL,           'setting', 90, NULL)
--     ('system.menu', '菜单管理', 'menu', '/system/menus', 'menu',    10, <system.id>)
--     ('system.role', '角色权限', 'menu', '/system/roles', 'team',    20, <system.id>)
--     ('system.dict', '数据字典', 'menu', '/system/dict',  'book',    30, <system.id>)
--     ('system.user', '用户管理', 'menu', '/system/users', 'user',    40, <system.id>)
-- 其余模块同构：目录 code = 模块名，菜单 code = 目录 code + '.' + 页面名；
-- path 与 icon 取值以 modules.tsx 注册表实际值为准。
--
-- 每个唯一 path 只落一条 menu 行：静态 MENU 中 /tenants、/dashboard/consolidate
-- 各出现两次，必须为它们指定唯一的归属目录；否则侧边栏会出现两个指向同一页面的
-- 入口，权限矩阵多出无意义行，而「数量一致」断言反而会强制保留重复。
-- 相应断言改为比较「去重后的 path 集合」，而非原始条目数量。
```

### 3.3 菜单管理页重做

替换当前 `/system/menus` 的 `ResourcePage` 配置，改为专用页面：

- 树形表格，列：名称 / 编码 / 路由 / 图标 / 类型 / 排序 / 状态 / 操作。
- 新建/编辑抽屉：类型、上级目录（菜单必选）、名称、编码、路由、图标、排序、状态。
- 校验规则：
  - `code` 全局唯一，创建后不可修改（修改需走专门的重命名流程，当前不做）。
  - 目录禁止填 `path`；菜单必须选择上级目录。
  - 菜单 `path` 与前端路由注册表交叉校验：未注册的路径在树上显示「未在路由注册表中」告警标记，保存时二次确认（而不是只在渲染阶段静默丢弃）。
  - 图标字段取自**具名图标目录** `ICON_BY_NAME`（见下），选择器带实时预览，取值写入 `menu.icon`。
- **具名图标目录（必做，新增）**：现有 `menuIcons.tsx` 只有 `PATH_ICONS`（按 path 索引）与 `GROUP_ICONS`（按中文分组名索引），**都无法解析 `menu.icon` 里存的名字**。需新增 `ICON_BY_NAME: Record<string, ReactNode>`（如 `folder` → `FolderOutlined`），供三处共用：3.3 的选择器、3.4 的侧边栏渲染、`V45` 种子取值。缺此目录时 `menu.icon` 无法渲染，DB 权威图标会静默退回注册表图标。
- 停用语义：
  - 停用目录 = 隐藏整个分组及其子菜单，**不论子菜单自身状态**；管理树仍以只读的「随目录停用」标记列出这些子项；已有 `role_permission` 行不受影响。
  - 停用单个菜单 = 该菜单从侧边栏隐藏，权限点保留。
- 删除保护：存在子节点或已被 `role_permission` 引用时，**只能停用，不能删除**。

### 3.4 前端侧边栏改造

- `AdminLayout` 改为读取 `GET /system/menus`（后端已返回「按权限过滤后的树」）。
- `modules.tsx` 的 `MENU` 降级为**路由注册表**：提供「path → 组件 / 标题兜底 / 图标」。
- 职责划分：DB 决定「显示哪些、顺序、命名」；注册表决定「怎么渲染」。**图标以 `menu.icon` 为权威**（经 `ICON_BY_NAME` 解析），注册表的 path 图标仅在 `menu.icon` 为空时兜底。
- 加载与失败态（必须有明确契约，**「空树」与「请求失败」是两件事**）：
  - 请求进行中 → 渲染侧边栏骨架屏。
  - **HTTP 失败 / 超时** → 回退到静态 `MENU`，但**必须先按 `/system/me` 的 `permissions` 过滤**；同时记录降级日志。
  - **成功返回 200 但树为空** → 这是「该用户确实没有任何页面权限」的合法结果，**渲染空导航态提示**，**不得**回退静态 `MENU`（否则无权限用户会看到 24 组 / 66 个入口，点进去全是 403，且破坏验收第 2 条）。
  - 「不显示」规则**仅适用于成功加载后树中确实缺失的节点**。
- 兜底策略：
  - DB 中存在但注册表缺失的节点 → 忽略并记录日志（避免脏数据白屏）。
  - 注册表存在但 DB 停用/缺失的节点 → 不显示。
  - **新增页面仍需前端注册表变更并随版本发布**：注册表只提供渲染元数据（path → 组件 / 标题兜底 / 图标），DB 无法凭空让一个未注册的页面可访问。
- `PageTabs`、`menuIcons`、`helpManual` 等依赖 `MENU` 的位置，统一改为消费「API 菜单树 + 注册表」的合并结果。

---

## 4. 角色权限管理设计

### 4.1 三层授权定义

| 层级 | 载体 | 判定码 |
|------|------|--------|
| 模块级 | 目录勾选 = 批量授予其下全部页面 | 目录 `code`（用于全选/半选 UI） |
| 页面级 | 菜单 `code` + `view` | `asset.ledger:view` |
| 操作级 | 菜单 `code` + 动作 | `asset.ledger:update` |

`MenuService.menusFor` **需要改造，不是「口径保持」**：菜单可见 ⇔ 角色拥有该 `code:view`；目录可见 ⇔ 其下任一菜单可见。

理由：现有实现以「`code` + `:`」前缀匹配，无法匹配点号分层的子编码（目录 `asset` 匹配不到 `asset.ledger`），照旧不动会导致**目录全部丢失、子菜单被提升为顶级**；同时现有逻辑对「任意动作」放行，会让只勾 `create` / `update` 的菜单可见、却被路由守卫判 403。改造后可见性只认 `view`。

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

-- 保存路径（必须实现）：由 menu_id 解析 menu.code 并回填 menu_code（该列保持 NOT NULL）；
-- 登录装配仍产出 menu_code:action，既有判定逻辑不变。否则权限集会写成 null:update，
-- 接口全部 403、侧边栏空掉。

-- 代码侧同步（必须与迁移同批）：RolePermission 实体新增 menuId、移除 dataScope。
-- 否则 MyBatis-Plus 仍会查询已删除的列，每个已认证请求都抛「列不存在」。
-- 注意 DROP COLUMN data_scope 是破坏性变更，应用后不可回滚。

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
  - **功能权限**：菜单树 × 动作勾选矩阵；目录行支持全选 / 半选。**尚未强制校验的动作必须加显式标识**（见 6.1 / 6.3），避免管理员误判「已生效」。该标识**必须由接口下发，不得前端硬编码**：`GET /system/roles/{roleId}/permissions` 与 `GET /system/permission-actions` 都要返回每个 `menu:action` 的 `enforced: true|false`（数据源见 6.3 的接入台账），否则前端只能复制一份 6.3 的 TODO 清单，必然与后端漂移。
  - **数据权限**：公司树 + 排除清单。勾选即排除，语义与全站其他树相反，必须用三种状态区分：**基线内**（可勾选）、**显式排除**（已勾选）、**继承排除**（父级已排除，置灰不可操作）。禁止排除管理员自己所属公司并内联说明；取消父级排除后子级勾选框恢复可用。
  - **成员**：该角色下的用户列表（数据源见 4.5）。
- 保存模型：每个角色一个保存按钮，一次提交「功能权限」与「数据范围」两个请求；存在未保存改动时切换角色或页签需二次确认；错误提示须指明两个写入中哪一个失败。
- 保存后即时刷新当前管理员的 `/system/me` 与菜单树；其他会话的刷新时机见 6.2。

### 4.5 接口清单

| 方法 | 路径 | 访问控制 | 说明 |
|------|------|----------|------|
| GET | `/api/v1/system/menus/all` | `system.menu:view` | 菜单完整树（管理页用） |
| POST/PUT/DELETE | `/api/v1/system/menus[/{id}]` | `system.menu:create/update/delete` | 菜单增删改（沿用，补充校验） |
| GET | `/api/v1/system/permission-actions` | `system.role:view` | 动作词表 + 每个 `menu:action` 的 `enforced` 标识（供矩阵渲染） |
| POST | `/api/v1/system/roles` | `system.role:assign` | 新建角色（含 `dataScope`，见下方提权约束） |
| PUT | `/api/v1/system/roles/{id}` | `system.role:assign` | 改角色名 / `dataScope` / 启停（见下方提权约束） |
| GET | `/api/v1/system/roles/{roleId}/permissions` | `system.role:view` | 返回「菜单树 + 已授动作 + 动作词表 + `enforced` 标识」 |
| PUT | `/api/v1/system/roles/{roleId}/permissions` | `system.role:assign` | 批量替换（`[{menuId, actions:[...]}]`） |
| GET | `/api/v1/system/roles/{roleId}/data-scope` | `system.role:view` | 默认范围说明 + 排除清单 + 候选公司树 |
| PUT | `/api/v1/system/roles/{roleId}/data-scope` | `system.role:assign` | 保存排除清单（`companyIds`） |
| GET | `/api/v1/system/users?roleId=` | `system.role:view` | 角色成员列表（现有用户接口新增 `roleId` 过滤） |
| DELETE | `/api/v1/system/roles/{id}` | `system.role:delete` | 删除角色；仍有成员绑定时拒绝并提示 |

**提权类接口的硬性约束**（`roles/{roleId}/permissions`、`roles/{roleId}/data-scope`，以及 `POST/PUT /roles` 中的 `dataScope` / `status` 字段）：

- 必须持有专门的 `system.role:assign` 能力，不能仅凭系统管理写权限。
- 调用者只能授予「自己已持有的动作」的子集；不得为任何已分配给自己的角色修改权限集或数据范围。
- **数据范围同样受限**：调用者写 `role.dataScope` 时，新值不得宽于调用者自身的数据范围（`all` 只能由持有 `all` 的调用者授予）。`dataScope = 'all'` 是本模型里最宽的一项权限，只管动作子集而不管它，等于给「有页面权限的人」留了造出全集团角色的后门。
- 违反任一条 → 403（复用 `ErrorCode.FORBIDDEN`）。
- `super_admin` 是本套约束的**唯一例外**（与 6.1 的拦截口径一致），用于首次授权引导；这也是 `system.role:assign` 的初始来源。

**审计**：菜单、角色权限、数据范围的全部写接口必须加 `@Audited`，使用专门的授权 / 数据范围动作编码，与 6.3 的接入清单一起跟踪。

---

## 5. 数据隔离设计

### 5.1 默认口径

`非 all 数据范围：可访问 = 所属公司 + 全部下级子树`
`all 数据范围：可访问 = 全部公司（仍受 5.2 排除清单约束）`

- A 公司与 B 公司无隶属关系 → 在非 `all` 范围内天然互斥，A 员工看不到 B 数据 ✅
- A 是 B 的（多层）上级 → 默认可见 B 数据 ✅
- `dataScope = 'all'` 的角色可跨公司，**不受互斥结论约束**，但仍会被排除清单削减。

**当前未实现的数据范围取值**：`dept` / `project` / `self` 本期一律按「所属公司 + 全部下级子树」处理（即退化为公司级），详见第 9 节。

### 5.2 新增：角色级排除清单

**算法（替代 `RbacService.companyScope`）**

```
scope(user):
    if user.companyScoped:
        # 已切换公司（X-Company-Id）：以切换公司为根收窄，保持现有语义
        baseline = {user.companyId} ∪ descendantIds(user.companyId)
    else:
        # 「不受限」**只有 super_admin** 走这条；dataScope == 'all' 不许早返回
        if user.isSuperAdmin():
            return UNRESTRICTED
        baseline = (dataScope == 'all')
                     ? ALL_COMPANIES
                     : {user.homeCompanyId} ∪ descendantIds(user.homeCompanyId)

    excluded = ∅
    for each role of user's enabled roles:
        for each companyId of role_data_exclude[role]:
            excluded ∪= {companyId} ∪ descendantIds(companyId)   # 命中即整棵子树

    allowed = baseline − excluded
    return allowed.isEmpty() ? {-1} : allowed     # 哨兵；绝不能"空 = 全量"
```

**易错点（务必按此实现）**：把 `dataScope == 'all'` 和 `super_admin` 写在同一个 `return UNRESTRICTED` 条件里，会让排除清单被整段跳过 —— 与 5.1「`all` 仍受排除清单约束」直接矛盾，管理员为 all 角色配的排除会**静默失效**。`all` 只影响 `baseline` 的取值，**必须照常执行减法与哨兵判定**。
**`UNRESTRICTED` 必须是一个具体值**（实现上可用 `null` 或专用常量），**不得用「空集合」表示**；空集合在本算法里恒等于哨兵 `{-1}`（无可见公司）。现有实现里**空集合 = 不限公司**，做减法后空集合若继续沿用该语义，就会从「排除干净」意外变成「放开全量」—— 这两者混同正是本设计要消除的越权来源。

**语义说明**：排除清单对 `all` 基线同样生效（正是「管理员限制访问范围」的用途）；`super_admin` 的例外**仅在未切换公司时成立**。
**切换收窄必须保留**：现有实现刻意在 `companyScoped` 时把范围收窄到切换公司，切换器对高权限账号才不是空操作；新算法必须保持该分支，否则切进子公司后仍看到整个集团。
**多角色合并**：默认范围仍取最宽（`widen`），排除清单取并集 —— 即任一角色排除的公司都生效。注意合并非单调：增加角色可能减少可见数据。

### 5.3 落点改造

- 改写 `RbacService.companyScope()`（见 5.2 算法），并支持切换公司语义。
- **排除数据装配（必做）**：`buildLoginUser` 查询 `role_data_exclude`，填充 `LoginUser.excludedCompanyIds` 供 `companyScope()` 消费；只取**已启用角色**（与 5.2 一致）。漏掉这一步会让排除永不生效。
- **停用角色即撤销**：权限装配同样只加载已启用角色的 `role_permission`，使停用角色同时撤销其权限与数据范围影响。
- **公司切换范围对齐（三个落点都要改，只改第一个无效）**：扣除排除子树必须同时改到位，否则被排除的公司仍会出现在切换器里、仍能被切换进去：
  - `RbacService.switchableCompanyIds()`：输出扣除排除子树；
  - **`RbacService.isSwitchable()`**：现有实现在 `unrestricted` 时**直接 `return true`**，决定头部切换是否放行 —— 必须改为「先判排除，再判 unrestricted」；
  - **`AuthService.switchableCompanies()`**：现有过滤写作 `unrestricted || allowed.contains(c.getId())`，`unrestricted` 为真时**根本不读 `allowed`** —— 必须改为「`allowed` 先扣排除子树，`unrestricted` 只用于跳过基线收窄」。
  - 与 5.2 的例外保持一致：`super_admin` **仅在未切换公司时**不受排除影响，此时其切换列表为全量；一旦切换到某公司，可见集合收窄为该子树。
- **数据范围覆盖清单（必做）**：`applyCompanyScope` / `companyScope` 目前仅被少数服务调用（资产项目、任务、告警、经营计划、固定资产、部门）。本次需逐一列出必须接入的读服务，并把**合同、账单、收款、支付等未接入的业务列表登记为显式缺口**——不能假设「改一处即全部继承」。
- **by-id 读路径同样要覆盖**：`applyCompanyScope` 只作用于列表查询，**按主键取详情完全绕过数据范围**。验收第 4 条要求「互不可见」，因此需与 6.1 的读侧对象级断言配对，并把详情类接口一并登记进上面的覆盖清单（只列列表服务会漏掉这条越权路径）。
- `LoginUser` 增加 `excludedCompanyIds`（供 `companyScope` 消费，并便于前端提示与调试）。

---

## 6. 操作级拦截闭环设计

### 6.1 后端

- 注解 `@RequiresPerm("asset.ledger:update")` + `PermissionInterceptor`（或 AOP 切面）。
- 判定逻辑：
  1. 无登录主体 → 401；
  2. `super_admin` → 放行；
  3. `rbacService.assertPermission(user, menu, action)` 不通过 → 403（复用 `ErrorCode.FORBIDDEN`）。
- 过渡开关 `ams.security.strict-perm`（默认 `false`），**两种取值的判定规则必须都写明**：
  - `false`（默认）：**已加注解**的接口照常判定；**未加注解**的接口保持「仅登录校验」。
  - `true`：**未加注解的「变更类」接口（POST / PUT / PATCH / DELETE）一律拒绝**；未加注解的 **GET 读接口**与 `SecurityConfig` 的 `permitAll` 白名单（登录、静态资源等）不受影响。
  - 这样定义后，「已接入模块强制、其余放行」只描述**默认档**的行为，不会与 `true` 档冲突；**首期交付的范围就是默认档**，不得表述为「已满足默认拒绝」。
- **数据范围校验（对象级，写路径）**，分三种情形，都必须实现：
  1. **更新 / 删除**：先按编号加载目标记录，用调用者的 `companyScope` 对**该记录在数据库中存储的公司归属**做断言；**不得以请求体传入的 `companyId` 作为校验依据**；记录的公司归属为空时，对受限用户视为拒绝 → 403。
  2. **新建**：没有已存记录，**请求体是唯一的归属来源** —— 必须对「请求体给出的归属公司（或由被引用父记录推导出的归属）」做 `companyScope` 断言后再落库；照抄第 1 条的字面表述会让新建**完全没有范围校验**，可以把项目 / 资产 / 合同直接建到范围外（含被排除子树）的公司。
  3. **归属派生（无公司列的实体必须定死）**：首批含合同、收费、退款、发票等表，这些表**本身没有公司列**（如 `contract` 只有 `asset_id` / `tenant_id`），必须沿引用链推导归属，例如 `contract → asset`、`bill / payment / invoice → contract → asset`，并统一规定**推导不到归属时按拒绝处理**。`asset` 上同时存在经营公司与产权公司两列，**必须明确以哪一列为准**（本设计取**经营公司**），否则同一账号会在一处放行、另一处 403。
- **读路径**：列表只有接入 `applyCompanyScope` / `companyScope` 的才受数据范围约束，覆盖清单见 5.3。**此外，按主键取详情的接口必须做与写路径同构的对象级断言**（加载记录 → 用其存储的公司归属做 `companyScope` 断言 → 越界 403 / 404）；否则持有一个 id 就能读到范围外甚至被排除公司的数据，验收第 4 条无法成立。

### 6.2 前端

- `usePerm()` → `can(menuCode, action)`；`<PermissionGuard perm="asset.ledger:update">` 包裹按钮。
- `ResourcePage` / `TableActions` 支持按权限声明显隐行操作与工具栏按钮。
- 路由守卫 `<RequirePerm code="asset.ledger">`：无 `view` → 403 页面。
- 登录后 `/system/me` 已返回 `permissions`，直接复用。
- **刷新时机契约（4.4 指向本节，实现必须以本节为准）**：
  - 权限快照在登录时取得，此后**不会自动变化**，必须有主动刷新点，否则改了权限的会话会长期用旧快照。
  - **保存权限的当前管理员**：保存成功后立即重新拉取 `/system/me` 与菜单树。
  - **其他已登录会话**：在**下次路由跳转**或**窗口获得焦点**时重新拉取 `/system/me` 与菜单树。
    - 频率按 **30s 节流**（`REFRESH_MIN_INTERVAL_MS`）：两个触发点在实际使用中都可能高频发生（一次 alt-tab 就是一次 `focus`），不做节流会退化成请求风暴。因此「生效」的时延上界是 30s，而不是「下一次跳转必定刷新」。
    - 快照无变化时不写入 state，避免「聚焦 → 权限未变 → 菜单树被无谓重拉」的级联。
  - 不做轮询、不做推送（与「生效时机：即时」一致 —— 后端每次请求实时组装，前端只是快照刷新）。

### 6.3 首批接入清单

**首批清单（枚举，须与选取标准一致）**：

1. 系统管理：角色 / 菜单 / 字典 / 用户；
2. 组织架构：公司 / 部门 / 人员；
3. 资产台账：项目 / 资产；
4. **收费 / 退款 / 发票 / 合同**等涉资金与法律责任的写接口。

**选取标准**：按「安全风险面 × 数据隔离密集度」优先，而非改动成本。按此标准，第 4 类与前三类同批，不得因「不在原枚举里」而顺延。其余模块登记 TODO 清单跟踪，并明确各自批次。

**接入台账（`enforced` 标识的数据源，必做）**：实现时需维护一份「已接入 `@RequiresPerm` 的 `menu:action` 清单」（可由注解扫描在启动时生成），经 4.5 的两个接口下发给前端，供 4.4 的矩阵渲染标识。没有这份台账，4.4 的标识就无法实现。

**完成定义（DoD）**：`strict-perm` 切为 `true` 的前提是**所有变更类接口（POST / PUT / PATCH / DELETE）都已完成注解**，而不是「首批清单清空」——首批只是交付批次，全量变更接口接入已在第 9 节列为本期不做，因此**切换动作本身属于后续交付，不在本期范围**。

未接入接口在过渡期为**已知缺口**（登录即可访问），不得视为已完成；权限矩阵上必须对未强制校验的动作加标识（见 4.4）。

---

## 7. 迁移与实施顺序

1. **迁移** `V45`：`menu` 唯一约束 + 菜单种子（每个唯一 path 一条，含 `icon`，系统管理 code 与 4.5 注解逐字对齐）；`role_permission` 改 `menu_id` + 唯一约束并删 `data_scope`（**破坏性，不可回滚**，需同步实体）；新增 `role_data_exclude`；**权限回填（按敏感性分区，不是全量 `view`）**：
   - **导航类菜单**：向现有非超管角色回填 `view`，保证首次部署不丢导航；
   - **系统管理菜单**（`system.menu` / `system.role` / `system.dict` / `system.user`）：**不回填**，只授予原有运维 / 管理员角色；否则 4.5 刚加的系统管理读接口对所有存量业务角色直接敞开；
   - **动作级回填（必须与注解上线同批）**：只回填 `view` 会导致注解一生效，所有非超管账号在首批模块上的 create/update/delete **全部 403**。选择其一并写入迁移：
     - (a) 按角色现有可达能力补回写动作（推荐，无中断）；或
     - (b) 明确「矩阵补动作」是注解上线的**前置人工步骤**，并在上线清单中显式列出。
2. **后端**：
   1. `PermissionAction` 枚举、`RolePermission` 实体同步；
   2. `RbacService.companyScope` 改造（含 `companyScoped` 分支、`all` 基线走排除、`UNRESTRICTED` 与空集合区分）与 `buildLoginUser` 的排除数据装配；
   3. **数据范围覆盖清单落地（5.3 必做）**：逐一列出必须接入 `applyCompanyScope` / `companyScope` 的**列表服务与详情服务**，把合同 / 账单 / 收款 / 支付等未接入列表登记为显式缺口 —— **这一步必须在本节里执行，不能只写在 5.3**，否则实现者会改完 `companyScope` 直接进入前端阶段，读侧隔离全部悬空；
   4. 菜单树 / 权限矩阵 / 数据范围三组接口、`AuthService.switchableCompanies` 与 `isSwitchable` 的排除对齐、提权约束。
3. **前端**：菜单管理页 + 角色权限页（替换 `ResourcePage` 配置）、侧边栏改读 API（保留静态兜底）。
4. **拦截闭环**：注解 + 拦截器 + `usePerm` + 路由守卫，按清单逐模块接入。

---

## 8. 验收标准

1. 菜单管理页以树展示目录/菜单，可对**已注册路由**改名、编辑、排序、启停；未注册路由显示告警标记；删除被引用节点被拒绝并提示（新增页面仍需前端注册表变更并随版本发布）。
2. 侧边栏内容与顺序来自 DB 菜单表；停用某菜单/目录后侧边栏即时消失；接口**失败**时回退静态菜单且**已按 `permissions` 过滤**；**成功但返回空树**时渲染空导航态而非全量菜单；DB 脏数据不会导致白屏。
3. 角色权限页可对「菜单 × 动作」矩阵勾选并保存；保存后当前管理员重新拉取 `/system/me` 即生效，其他会话在下次路由跳转或窗口获得焦点后生效；前端按钮与后端接口行为一致。
4. A、B 无隶属公司互不可见（`dataScope = 'all'` 的角色除外）；A 为 B 上级时默认可见 B；在角色上排除 B 后，A 的员工不能再看到 B 的数据（含下级），且 B 不再出现在公司切换器中。
5. 排除清单清空后行为回到默认子树；`super_admin` 仅在**未切换公司**时不受排除影响；切入某公司后可见集合收窄为其子树。
6. 未授予 `asset.ledger:update` 的账号调用资产更新接口返回 403，前端不展示对应按钮。
7. 非 `super_admin` 且无 `system.role:assign` 的账号调用角色权限保存或数据范围保存接口返回 403；调用者不能授予自己未持有的动作，也不能修改自己所属角色的权限集，**也不能把 `role.dataScope` 写到宽于自身**（无 `all` 者不得授予 `all`）。
8. 未授予对应动作的账号调用**收费 / 退款 / 发票 / 合同**中任一写接口返回 403；授予后成功 —— 首批第 4 类模块须有可执行用例，不得只测资产台账。
9. 数据范围在**详情读**上同样生效：以 A 公司账号按主键请求 B 公司（或被排除公司）的资产 / 合同详情返回 403；**新建**时把请求体归属公司写成范围外公司同样被拒；对无公司列的实体（合同 / 账单 / 收款 / 发票）沿引用链推导归属，推导不到时拒绝。
10. 角色权限矩阵上「未强制校验」的动作标识来自接口下发的 `enforced` 字段（不由前端硬编码）；后端接入台账变更后，刷新页面即反映最新标识。

---

## 9. 本期不做（Out of Scope）

- 菜单三级及以上层级、动态路由按需注册（前端路由仍为编译期注册表）。
- 按「模块 × 公司」的细粒度数据范围矩阵（`role_permission.data_scope` 直接移除）。
- 用户级数据范围覆盖（仅角色级排除清单）。
- **`dept` / `project` / `self` 数据范围**：本期一律退化为公司级（见 5.1），角色表单需求方需同步收敛或加提示，避免管理员误以为已限制范围。
- 权限缓存层（当前每请求实时组装；未来如需可加短 TTL 缓存）。
- 全量模块的操作级拦截接入（本期仅框架 + 核心模块）。
- **把 `strict-perm` 切为 `true`**：该动作以「全部变更类接口完成注解」为前提（见 6.3），而全量接入已列为本期不做，因此**切换属于后续交付**。本期交付的是「默认档 = 已接入强制、其余放行」，并附 4.4 的未强制标识与 6.3 的接入台账，让缺口可见。

---

## 10. 风险与取舍

| 风险 | 应对 |
|------|------|
| 侧边栏改读 DB 依赖种子完整性，漏种子会导致菜单缺失 | 路由注册表仅提供渲染元数据（标题 / 图标 / 组件），**无法恢复 DB 中缺失的菜单**；实际兜底是 `V45` 种子与静态 `MENU` 逐节点对齐，并加测试断言路径集合一致 |
| 两级菜单上限可能不满足未来需求 | 本期明确上限；如需三级再改树组件与校验 |
| `role_permission` 从 `menu_code` 改为 `menu_id` 影响既有判定 | 无历史权限数据，迁移为纯结构变更；保留 `menu_code` 冗余用于日志与兜底 |
| 渐进拦截期间存在未接入接口 | 用 TODO 清单显式跟踪；`strict-perm` **本期不切换**（见 9），并在矩阵上标识未强制动作（见 4.4） |
| 注解上线当天非超管账号写操作大面积 403 | `V45` 回填必须同时覆盖写动作或把「矩阵补动作」列为上线前置步骤（见 7.1），二者择一，不能只回填 `view` |
| 「空 = 全量」语义被破坏引发越权 | 引入哨兵 `{-1}` 显式表达「无可见公司」，并补单测覆盖排除到空的场景；`UNRESTRICTED` 用独立值表示，不得复用空集合 |
| 排除清单对 `all` 角色静默失效 | 5.2 禁止 `all` 早返回；`all` 只决定基线取值，减法与哨兵照常执行，并加单测覆盖「all 基线 + 排除」 |

---

## 11. 实现记录：偏差与已登记缺口

本节记录实现过程中与正文不一致的地方、以及按 5.3 要求必须显式登记的缺口。
**不是「已完成」清单** —— 未列出的项不代表已验证（本机无 JDK/Maven，后端未经编译验证）。

### 11.1 与正文的偏差（有意为之）

| # | 正文写法 | 实际实现 | 原因 |
|---|----------|----------|------|
| D1 | 3.2 示例把「用户管理」写作 `system.user`，7.1 的「不回填系统管理菜单」按 `system.*` 前缀排除 | 种子里是 **`org.user`**（`/system/users` 在 `modules.tsx` 里归在「组织架构」目录下） | 3.2 已声明「path 与 icon 取值以 modules.tsx 注册表实际值为准」，图标/路径服从注册表时编码随之偏离示例。**只按 `system.%` 前缀排除回填会让「人员维护」连同 `org.user:view` 一起发给全部业务角色**，等于对所有人敞开人员管理。V45 已显式加 `AND m.code <> 'org.user'`，并在 5.2 把它与 `system.*` 一同只授 `operator` |
| D2 | 4.5 表格：`GET /system/users?roleId=` 要求 `system.role:view` | 同时要求 `org.user:view`（注解）**与** `system.role:view`（程序化） | 成员列表会返回姓名 / 手机号等个人信息，只有角色管理权、没有人员读权限的账号不应看到。比正文更严，方向上安全 |
| D3 | 4.5 表格：`PUT /system/roles/{roleId}/permissions` | 由 `POST` 改为 `PUT`，请求体由 `List<RolePermission>` 改为 `{items:[{menuId, actions}]}` | 正文即写 `PUT`；旧 `POST` 从未被前端使用（1 章已核对），故无兼容负担。改为按 `menuId` 提交是为了让服务端解析回填 `menu_code`，杜绝客户端伪造 code |
| D4 | 4.5 表格：`GET /system/menus/all` 说明为「菜单完整树」 | 返回值由扁平 `List<Menu>` 改为树 `List<MenuNode>` | 依正文。**这是破坏性变更**：U7（菜单管理页）落地前，现有 `/system/menus` ResourcePage 无法解析该响应 |
| D5 | 未指定 | `finance` 的 `/bank-flows`、`/vouchers`、`/month-closes` 映射到 `finance.bankFlow:*` / `finance.voucher:*`；月结（`/month-closes`）无专属菜单，归入 `finance.voucher:*` | 种子里的财务菜单只有 bankFlow/voucher/invoice 三项，月结无独立页面。属判断项，若需独立授权应补菜单种子 |
| D6 | 6.1：读详情越界「403 / 404」 | 统一 403（`DATA_SCOPE_FORBIDDEN`） | 与 `assertCompanyAccess` 的既有语义一致，避免同一越权在两处返回不同状态码 |
| D7 | 3.4：接口失败「回退到静态 `MENU`，但必须先按 `permissions` 过滤」 | 额外落了一份 `PATH_TO_CODE` 镜像（`routeRegistry.ts`，由 V45 生成），并在接口成功时做漂移自检 | 静态 `MENU` 只有 `path` / `title`，**没有权限码**，而过滤必须用 `code:view` 判定 —— 没有镜像就只能把整个菜单原样显示给无权限账号。镜像的漂移风险被两条措施夹住：① 生成而非手抄；② 接口成功时逐条比对，不一致即 `console.warn` 列出差异（仅开发环境）。漂移后果也限于「接口失败」这一条路径，方向安全（缺条目→隐藏；多条目→点击 403，后端仍拦） |
| D8 | 3.4：图标「`menu.icon` 为权威，注册表图标兜底」 | 图标优先级判定只放在**渲染处**一处（`AdminLayout` 的 `item.icon ?? getPathIcon(...)`），`MenuProvider` 只负责把 `menu.icon` 的名字解析成节点、不做兜底 | 若 provider 也兜底，同一个优先级规则会有两份实现，改一处漏一处就会出现「侧栏与页签图标不一致」。`getIconByName` 对未登记名字返回 `undefined`（而非默认图标），以区分「DB 明确指定但拼错」与「DB 没配、该走兜底」 |
| D9 | 3.4 未提及静态兜底菜单的分组图标 | 降级态不填 `icon`，同样落回 `getGroupIcon(title)` | 与 D8 同一原则；静态 `MENU` 本就没有 DB 图标 |
| D10 | 3.4 只要求「侧边栏改读 API」 | `PageTabs`（页签标题）与 `helpManual`（用户手册条目）一并改为消费同一份 `groups` | 两者原先都从静态 `MENU` 取 `title`。菜单在 DB 改名后，若它们仍读静态表，会出现「侧栏叫 A、页签叫 B、手册叫 C」。`HELP_MANUAL` / `HELP_GROUPS` 因此由常量改为 `buildHelpManual(groups)` / `helpGroups(manual)` 两个纯函数 |
| D11 | 3.3 列清单：名称 / 编码 / 路由 / **图标** / 类型 / 排序 / 状态 / 操作 | 不单开「图标」列，图标渲染在「名称」前（即侧边栏的真实样子） | 两列并排会把同一个图标显示两遍；按侧边栏的样子展示才能一眼看出「配错图标」。校验能力不减（未登记的图标名会渲染成灰色占位，选择器也带实时预览） |
| D12 | 3.3：「图标字段取自具名图标目录 `ICON_BY_NAME`」 | `getIconByName` 对未登记的名字返回 `undefined`，调用方据此渲染占位图标并可用 `console` 定位 | 若返回默认图标，「DB 里写了 `ledgerr`」与「DB 里没配、该走兜底」在界面上完全一样，配错图标将无法被发现。选择器只提供已登记名字，因此该分支只可能由手工改库/迁移脚本触发 |
| D13 | 未指定（3.3 的删除保护只说明「只能停用不能删除」） | 删除按钮不按「有无下级」提前置灰，一律提交后端判定并原样透出原因 | 两条保护里「已被 `role_permission` 引用」前端无从判断（需查授权表），只能靠后端；两条都靠后端才能给出一致的可读原因（「存在下级菜单，只能停用不能删除」），比为一半情况置灰一个说不出原因的按钮更好 |
| D14 | 6.1：`super_admin` 在前端判定口径未明 | 新增 `lib/perm.ts`（`hasPerm` / `usePerm`），超管恒真，其余按 `menuCode:action` **精确匹配**；`LoginUser` 增补可选字段 `superAdmin` | 与后端 `LoginUser.hasPermission` 口径对齐（旧实现是 `code + ':'` 前缀匹配，会放行「只有 create 却判 view」）。字段可选是因为老会话的 `localStorage` 快照里没有它，缺失时按 `false` 处理（安全侧）。U9 在其上补 `PermissionGuard` 与路由守卫 |
| D15 | 4.4：数据权限三态「基线内 / 显式排除 / 继承排除」 | 三者**严格互斥**：`collectInheritedExcluded` 返回的继承集合**排除掉显式项**，被上级覆盖的显式项另打「上级已排除」提示且**保持可取消** | 若把显式项也算作继承，它会被置灰而无法取消：而「排除父公司 → 再排除子 C → 取消父公司」这条常见操作会把 C 的显式排除一并丢掉，让该角色**悄悄重新获得 C 的访问权**（安全侧的静默放宽）。区分后计数也不再重复（原写法会双重计入） |
| D16 | 4.4：「取消父级排除后子级勾选框恢复可用」 | 同上，另加环形 `parentId` 兜底（遍历用 `visited`、向上找祖先用 `seen`） | 库里的环形脏数据会让树成环：没有去重时遍历无限循环，页面卡在加载态且不报错，排障成本极高 |
| D17 | 4.4：「保存后即时刷新当前管理员的 `/system/me` 与菜单树」 | `AuthProvider` 增补 `refreshUser()`（`GET /system/me`）；角色权限页保存成功后调用 `refreshUser()` + `MenuProvider.reload()` | 权限快照只在登录时写入，不刷新会表现为「刚给自己的角色勾了菜单，侧栏却没出来」。6.2 的其余触发时机（路由跳转、窗口聚焦）仍由 U9 负责 |
| D18 | 7.1：动作级回填「(a) 按角色现有可达能力补回写动作（推荐，无中断）或 (b) 前置人工步骤，二者择一」 | 采纳 **(b)**：V45 §5.3 写入显式警示注释与上线前置步骤清单，**不回填任何写动作** | **选项 (a) 的前提被证伪**：本迁移之前 `menu` / `role_permission` 均为空表（全仓除 V45 外无 `INSERT INTO menu` / `INSERT INTO role_permission`），且基线 `1f7ddd7` 全仓 `@PreAuthorize`/`@RequiresPerm` 计数为 **0** —— 「现有可达能力」实为「谁都能写」。照 (a) 回填等于给 `leader`（决策层，本应只读）与 `approver` 也授予全模块 create/update/delete。选 (b) 后失效模式是**明确的中断**（写接口 403，可观测可回滚），而误回填的宽权限是**静默的**且会长期留在库里无人复核 |
| D19 | 6.2：「其他会话在下次路由跳转或窗口获得焦点时重新拉取 `/system/me` 与菜单树」 | 新增 `<PermissionSnapshotRefresher/>`（挂在 `AdminLayout`）实现两个触发点，但加 **30s 节流**（`REFRESH_MIN_INTERVAL_MS`）；同时 `refreshUser` 在快照无变化时不写入 state | 设计只写触发时机未限定频率，而窗口聚焦在实际使用中一次 alt-tab 就是一次事件；不节流会退化成「每次切换两个请求」并在失败时叠加。无变化不写 state 还顺带切断了「聚焦 → 权限未变 → 菜单树被无谓重拉」的级联（菜单以 `user.permissions` 为依赖） |
| D20 | 6.2：「`ResourcePage` / `TableActions` 支持按权限声明显隐行操作与工具栏按钮」 | `ResourcePage` **无需逐页配置**：由当前路由 path 经 `PATH_TO_CODE` 解析出 `menuCode`，自动判定 create/update/delete/export/import，并据此决定「操作」列是否整列消失；`TableActions` 增补 `perm?: string`（完整 `menuCode:action`），由组件统一剔除无权项 | 两者口径不同是**刻意的**：`ResourcePage` 的调用点是路由，menuCode 可推导（自动、零配置）；而独立页面的一张表里可能混着 view/export/approve 等不同动作，**推导不出**，猜错会把可用按钮藏掉，故那里必须显式声明，且**未声明 = 不判定**（保持既有行为，不产生静默回归）。`ResourcePage` 中未声明 `perm` 的 `rowActions` 同理不判定 |
| D21 | 6.2：「路由守卫 `<RequirePerm code="asset.ledger">`：无 `view` → 403 页面」 | `<RequirePerm>` 的 `code` **可省略**，省略时按当前 path 推导；渲染位置在 `AdminLayout` 内容区内（保留侧栏与页签），403 文案带上判定码 | 一处在布局里包住 `<Outlet/>` 即可覆盖全部路由，不必逐条 `<Route>` 包一层（漏包一条就是静默无守卫）。不整页跳转是因为整页 403 会把导航一起藏掉，把人困在只能后退的界面上；带判定码是为了让反馈能直接定位到矩阵的哪一行。**`PATH_TO_CODE` 镜像外的 path（`/assets/:id/dossier` 等钻取路由）与 `user` 缺失时放行**，安全边界仍在后端 |

### 11.2 已登记的缺口（未接入，仍需跟踪）

| 缺口 | 影响 | 说明 |
|------|------|------|
| **列表级数据范围未覆盖合同 / 账单 / 收款 / 退款 / 发票** | 受限账号的列表请求仍可能返回范围外记录（详情与写路径已堵住） | 这些表无公司列，列表过滤需要 `JOIN contract → asset`，属独立改造。**本轮只做了 by-id 读与写路径的对象级断言**，5.3 要求的列表中「合同、账单、收款、支付等未接入列表」即指此处 |
| **`org.structure` 图谱未做数据范围收敛** | 图谱节点 `/graph` 会按 `rootId` 展开整棵子树，不校验 `rootId` 是否在范围内 | 仅有权限级拦截（`org.structure:view`），无对象级断言 |
| **财务银行流水 / 凭证 / 月结无数据隔离** | 这些表无任何公司归属链路，`OwnershipResolver` 无法推导 | 仅权限级拦截。需要业务上先确定归属口径（按账户？按公司？） |
| **程序化断言不进 `enforced` 台账** | `UserController.page` 的 `system.role:view` 检查是程序化的，不在 `@RequiresPerm` 扫描结果里 | 权限矩阵会把该动作标为「未强制校验」。方向上是**低报**（安全侧），但会造成管理员误判，需要时可为台账增加程序化登记入口 |
| **`strict-perm` 未切换** | 未加注解的变更类接口仍可访问 | 与 9 节一致：切换以「全部变更类接口完成注解」为前提，属后续交付。本轮已交付默认档 + `enforced` 标识 |
| **`STANDALONE_ROUTES` 与 `App.tsx` 是双写** | 新增独立页面时只改了 `App.tsx`、漏改注册表 → 该路由不在 `ROUTE_REGISTRY` 里，**DB 菜单行会被当脏数据忽略，侧栏永远不显示该入口** | 需要双写是「菜单只渲染已注册路由」这条规则的必然代价（一个在编译期、一个在运行期，无法互相推导）。缓解：`MenuProvider` 会把被忽略的 DB 菜单 `console.warn` 出来（`[menu] ... 未在前端路由注册表中`），漏改能从首屏日志直接看到。后续可考虑改为从 `App.tsx` 的 `children` 反推注册表 |
| **上线前置人工步骤：矩阵补写动作（D18 的落点）** | 未执行时非超管账号在**首批已接入模块**的写接口全部 403（导航与只读不受影响） | V45 §5.3 已写明步骤：super_admin 登录 → 角色权限页 → 逐角色勾选 create/update/delete 并保存。这是 6.3「DoD 以全部变更类接口注解完毕为前提」在本轮的实际前置条件，**必须进上线清单**；漏执行的表现是明确可观测的 403，不是静默越权 |
| **`TableActions` 的 `perm` 逐页补声明：10 个调用点中 4 个已声明、6 个刻意不声明** | 未声明的 6 处仍**只按后端 403 拦截，前端不做显隐** | **声明口径：只对「后端确实强制校验了该接口」的动作声明**。V45 之后 `role_permission` 只有 `view` 行，因此任何写动作声明都会让按钮对**除超管外的所有人**消失；若该接口并未接入拦截，就等于**把还能用的按钮藏起来**（比后端行为更严，属功能损失）。据此逐处判定：<br>**已声明（接口受强制校验）**：`SystemDictionaryPage`（`system.dict:create/update/delete`，路由码与接口码一致）、`ProjectFormPage` 提交（`asset.project:create`/`update`，按 `isEdit` 取码）、`PaymentConfirmPage` 确认到账（`finance.payment:update`）、`AssetDossierPage` 查看合同（`contract.ledger:view`）。<br>**刻意不声明（接口未接入，声明反而误伤）**：`ApprovalPage` 通过/驳回（工作流接口无注解）、`DashboardPage` 下钻/查看资产（纯导航）、`DunningAutoPage` 去处理（`to: '/tasks'` 链接）、`AgentReportsPage` 下载（`intelligence.*` 无注解）、`ContractTemplatesPage` 预览/编辑（`contract.template` 无注解）。<br>**顺带登记命名差异**：`PaymentConfirmPage` 的路由码是 `finance.paymentConfirm`，而它调用的 `POST /payments/{id}/confirm` 受 `finance.payment:update` 校验 —— 判定必须用**接口码**，用路由码会得到「配了也不生效」。同一张表里混着不同模块的动作时同理（如「生成合同」属于 `contract.ledger:create`），这正是 `TableActions` 不做自动推导、只接受显式全码的原因 |
| **跨仓不变量无自动化守卫** | 前端声明的 `perm` 若写错（拼写/取错码/后端改了注解），表现是「按钮静默消失」或「点了 403」 | 本轮以一次性脚本核对：前端所有 `perm` 声明（`perm: '...'` / `perm="..."` / 三元两分支）必须 ⊆ 后端 `@RequiresPerm` 集合（当前 9 个声明 / 57 个强制码，全部命中）。**尚未落成 CI 检查**，后续应固化 —— 它同时能拦住「声明了未接入的码」这类功能性误伤 |

### 11.3 本轮的启动期强校验（新增，非正文要求）

`PermissionRegistry` 在启动时校验每个 `@RequiresPerm`：
1. 格式必须为 `menuCode:action`；
2. `action` 必须在 `PermissionAction` 词表内；
3. `menuCode` 必须在 `menu` 表中真实存在（`menu` 表为空或读不到时降级为告警）。

任一不满足 → **启动失败**。理由是这三类错误上线后的表现都是「该接口对所有非超管角色永久 403」，
排障成本远高于启动失败；而 4.5 特别提示的 `system.menu` / `system.menus` 拼写不一致正是第 3 条要拦的情况。
降级条件（测试环境关闭 Flyway、迁移未执行）都不是「编码写错」，不应因此让应用起不来。

