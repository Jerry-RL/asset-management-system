# PC 管理后台样式对齐设计稿（第一期）

**日期**：2026-09-08  
**状态**：已确认，实现中  
**范围应用**：`frontend/admin-web`  
**设计稿来源**：`docs/source-material/`（用户口中的 `@docs/material`）

---

## 1. 背景与目标

当前 `admin-web` 使用 Tailwind 通用灰蓝样式（深色侧栏、居中登录卡、自研简易表格），与 `docs/source-material` 中「资管云平台」设计稿差距明显。

**总体目标（长期）**：三端（`admin-web` / `h5-tenant` / `h5-worker`）全量像素级对齐设计稿。

**第一期目标（本期）**：在 PC 端建立可复用的设计体系与通用列表模板，并完成壳层 + 登录 + 应用中心首页，使现有模块路由批量继承新视觉，为后续逐页像素还原与 H5 对齐打底。

---

## 2. 已确认决策

| 项 | 决策 |
|----|------|
| 覆盖端（长期） | 三端全做（C） |
| 对齐深度（长期） | 尽量全量像素还原（C） |
| 第一期切分 | PC 全局设计体系（token + 通用组件）再批量套用（B） |
| 组件库 | 壳层 Tailwind；表格/表单/分页用 antd（C） |
| 壳层范围 | 含白底侧栏、顶栏/多页签、登录页、应用中心首页（A） |
| 落地顺序 | 地基优先：token/主题 → ResourcePage → Layout → 登录/首页（方案 2） |

---

## 3. 本期范围

### 3.1 In Scope

- 设计 token（CSS 变量）+ antd `ConfigProvider` 主题（主色、圆角、表格/按钮/标签）
- `AdminLayout`：白底侧栏、顶栏、多页签
- `LoginPage`：左右分栏登录（对照 `登录页/登录页面.png`）
- 应用中心首页 `HomePage`（对照 `首页/首页页面.png`）
- `ResourcePage` 升级为设计稿风格列表模板（对照 `资产管理模块/资产台账页面.png`）
- 现有 `RESOURCES` 路由批量继承新壳与新列表模板
- 菜单：`/` 改为应用中心；经营看板迁至 `/dashboard`

### 3.2 Out of Scope（本期不做）

- `h5-tenant` / `h5-worker` 样式对齐
- 经营看板图表像素级还原（可保留简化卡片）
- 各模块专属详情页/复杂业务 UI 定制
- 强制使用设计稿原插画资源文件（可用渐变/占位图）
- 引入 Ant Design Pro 整套 ProLayout（仅用 antd 基础组件）

---

## 4. 视觉与 Token

从设计稿提取的约定（实现时可微调具体色值，但语义保持一致）：

| Token | 用途 | 参考 |
|-------|------|------|
| `--ams-primary` | 主色（按钮、激活侧栏、页签下划线、链接） | `#1677ff`（与 antd 默认蓝对齐，可按设计稿微调） |
| `--ams-bg` | 内容区背景 | `#f5f6f8` |
| `--ams-sidebar-bg` | 侧栏背景 | `#ffffff` |
| `--ams-sidebar-active-bg` | 侧栏选中 | 主色 |
| `--ams-sidebar-active-text` | 侧栏选中文字 | `#ffffff` |
| `--ams-card-radius` | 卡片圆角 | 6–8px |
| `--ams-border` | 边框 | 浅灰 |

品牌文案：登录/顶栏对齐设计稿「资管云平台」（可保留副标题 Asset Management）；内部系统名可在页脚或关于信息中保留「资产经营管理系统」。

文件：

- `src/theme/tokens.css`
- `src/theme/antd.ts`（导出 theme 对象）
- `src/index.css` 引入 tokens；`main.tsx` 包裹 `ConfigProvider` + `zh_CN`

---

## 5. 组件与结构

```
admin-web/src/
  theme/
    tokens.css
    antd.ts
  components/
    AdminLayout.tsx      # 侧栏 + 顶栏 + 多页签 + Outlet
    PageTabs.tsx         # 多页签状态（可选拆出）
    ResourcePage.tsx     # 列表模板（antd Table/Form/Pagination）
  pages/
    LoginPage.tsx
    HomePage.tsx         # 应用中心（新建）
    DashboardPage.tsx    # 经营看板（从 / 迁到 /dashboard）
    modules.tsx          # MENU 路径调整；RESOURCES 可扩展可选字段
```

### 5.1 AdminLayout（Tailwind）

- 白底侧栏；分组可折叠；当前项蓝底白字
- 顶栏：Logo/平台名、侧栏折叠按钮（可选）、用户信息/退出
- 多页签：访问路由自动添加；首页（应用中心）固定不可关；关闭当前页跳邻页；建议 `sessionStorage` 恢复打开列表
- 内容区：浅灰底 + 内边距；页面内容白底卡片感由各页自行保证

### 5.2 LoginPage

- 全屏：左侧浅色渐变/插画区 + Logo；右侧白卡片表单
- 字段：账号、密码、验证码（与现有 `/auth/captcha` 逻辑不变）
- 主色全宽登录按钮

### 5.3 HomePage（应用中心）

- 欢迎条（渐变卡片）
- 工作台区域（可先占位）
- 待办任务区域（可先空态）
- 应用中心四象限：经营性资产 / 固定资产 / 数智管理 / 无形资产；入口链接到现有模块 path

### 5.4 ResourcePage（antd + Tailwind）

升级为模板，`ResourceConfig` 向后兼容并可选扩展：

| 字段 | 说明 |
|------|------|
| 现有字段 | `title` / `listPath` / `columns` / `fields` / `filters` 等保持 |
| `stats?` | 顶部统计条（label + value） |
| `tagFilters?` | 标签式筛选（含「不限」） |
| 工具栏 | 新建（若 `create`）、搜索、刷新；导入/导出可占位禁用或隐藏 |

交互：列表加载、详情抽屉/弹窗、新建表单、分页；API 调用方式与现实现保持一致，避免功能回退。

---

## 6. 路由映射

| 路径 | 组件 | 设计稿 |
|------|------|--------|
| `/login` | `LoginPage` | `登录页/登录页面.png` |
| `/` | `HomePage` | `首页/首页页面.png` |
| `/dashboard` | `DashboardPage` | `经营看板/经营看板页面.png`（简化） |
| `/dashboard/consolidate` | `ConsolidatePage` | 白卡片样式套用 |
| `RESOURCES` 各 path | `ResourcePage` | 以资产台账列表为模板标准 |

`MENU` 调整：

- 「首页与工作台」：`/` → 应用中心；`/dashboard` → 经营看板；保留集团合并看板
- 其余业务 path 不变

---

## 7. 依赖

- 新增：`antd`、`@ant-design/icons`
- 保留：React Router、Tailwind、现有 auth/api
- 不引入：`@ant-design/pro-components`（本期）

---

## 8. 验收标准

1. 登录页为左右分栏，主色按钮与验证码行齐全，视觉接近设计稿
2. 登录后默认进入应用中心；四象限入口可导航到对应模块
3. 侧栏白底 + 当前项蓝底白字；顶栏与多页签可用
4. 任意 `ResourcePage` 列表具备标题、筛选、工具栏、表格、分页，风格统一
5. antd 主色与 CSS token 一致；无明显深色侧栏/无主题旧样式残留
6. 登录、列表查询、详情、新建等现有能力不回退

---

## 9. 风险与后续

| 风险 | 缓解 |
|------|------|
| 插画资源缺失 | 渐变/几何占位，不阻塞主流程 |
| 菜单与设计稿分组不完全一致 | 本期保 path，分组文案逐步贴近 |
| H5 尚未对齐 | 第二期起复用 token 语义，另开规格 |

**建议后续分期**：

1. 本期：PC token + 壳 + 登录 + 首页 + ResourcePage 批量
2. PC 经营看板/台账等关键页像素加深
3. `h5-tenant` 对齐小程序设计稿
4. `h5-worker` 对齐

---

## 10. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v0.1 | 2026-09-08 | 初稿，待用户审阅 |
